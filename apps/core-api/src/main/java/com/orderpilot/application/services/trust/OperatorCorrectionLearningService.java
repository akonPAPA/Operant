package com.orderpilot.application.services.trust;

import com.orderpilot.api.dto.OperatorCorrectionLearningDtos.CorrectionLearningProjectionResponse;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.common.errors.ConflictException;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.domain.audit.AuditEvent;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.trust.ai.AiMemorySourceType;
import com.orderpilot.domain.trust.events.TrustAiDomainEvent;
import com.orderpilot.domain.trust.events.TrustAiEventType;
import com.orderpilot.domain.trust.learning.OperatorCorrectionLearningRecord;
import com.orderpilot.domain.trust.learning.OperatorCorrectionLearningRecordRepository;
import com.orderpilot.domain.trust.learning.OperatorCorrectionStatus;
import com.orderpilot.domain.trust.learning.OperatorCorrectionType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-18 Operator Correction Learning Loop — capture + governance of operator corrections.
 *
 * Captures sanitized operator-correction metadata and governs its promotion into advisory AI memory. Raw
 * previous/corrected values are SHA-256 hashed and discarded — never persisted or logged. Learning
 * eligibility is deterministic; an operator correction does not become high-authority automatically. Only
 * explicit approval queues a record for projection (by publishing an idempotent
 * {@code OPERATOR_CORRECTION_RECORDED} event the projector consumes). Price/stock corrections are recorded
 * for traceability but can never be approved into authoritative memory. All actions are tenant-scoped and
 * audited.
 */
@Service
public class OperatorCorrectionLearningService {
  public static final int DEFAULT_LIMIT = 25;
  static final int MAX_LIMIT = 100;
  static final int MONEY_SCALE = 4;
  static final int MAX_NORMALIZED = 256;
  static final int MAX_SUMMARY = 512;
  static final int MAX_TARGET_TYPE = 48;
  static final int MAX_FIELD_KEY = 64;
  static final int MAX_REASON = 280;

  private final OperatorCorrectionLearningRecordRepository corrections;
  private final TrustAiEventPublisherService eventPublisher;
  private final AuditEventRepository auditEvents;
  private final JsonSupport jsonSupport;
  private Clock clock;

  public OperatorCorrectionLearningService(
      OperatorCorrectionLearningRecordRepository corrections,
      TrustAiEventPublisherService eventPublisher,
      AuditEventRepository auditEvents,
      JsonSupport jsonSupport,
      Clock clock) {
    this.corrections = corrections;
    this.eventPublisher = eventPublisher;
    this.auditEvents = auditEvents;
    this.jsonSupport = jsonSupport;
    this.clock = clock;
  }

  public record RecordCorrectionCommand(
      UUID tenantId, OperatorCorrectionType correctionType, AiMemorySourceType sourceType, UUID sourceId,
      String targetType, UUID targetId, String fieldKey, String previousValue, String correctedValue,
      String normalizedCorrection, String correctionSummary, BigDecimal confidence, UUID createdBy) {}

  // ----------------------------- record -----------------------------

  @Transactional
  public OperatorCorrectionLearningRecord recordCorrection(RecordCorrectionCommand cmd) {
    UUID tenantId = required(cmd.tenantId(), "tenantId");
    OperatorCorrectionType correctionType = required(cmd.correctionType(), "correctionType");
    AiMemorySourceType sourceType = required(cmd.sourceType(), "sourceType");
    String targetType = bound(requireText(cmd.targetType(), "targetType"), MAX_TARGET_TYPE);
    String correctionSummary = bound(requireText(cmd.correctionSummary(), "correctionSummary"), MAX_SUMMARY);
    BigDecimal confidence = normalizeConfidence(cmd.confidence());

    // Raw values are hashed and discarded — never stored or logged.
    String previousHash = sha256Hex(cmd.previousValue());
    String correctedHash = sha256Hex(cmd.correctedValue());
    String normalized = bound(cmd.normalizedCorrection(), MAX_NORMALIZED);

    // Deterministic learning eligibility: blocked types never eligible; low-confidence not eligible at
    // record time (an explicit approval can still promote it later).
    boolean learningEligible = correctionType != OperatorCorrectionType.PRICE_OR_STOCK_CORRECTION_BLOCKED
        && confidence.compareTo(AiMemoryPolicyService.MIN_USABLE_CONFIDENCE) >= 0;

    Instant now = clock.instant();
    OperatorCorrectionLearningRecord record = corrections.save(new OperatorCorrectionLearningRecord(
        tenantId, correctionType, sourceType, cmd.sourceId(), targetType, cmd.targetId(),
        bound(cmd.fieldKey(), MAX_FIELD_KEY), previousHash, correctedHash, normalized, correctionSummary,
        confidence, learningEligible, cmd.createdBy(), now));
    recordAudit(tenantId, "AI_OPERATOR_CORRECTION_RECORDED", record, cmd.createdBy());
    return record;
  }

  // ----------------------------- approve / reject -----------------------------

  @Transactional
  public CorrectionLearningProjectionResponse approveCorrectionForLearning(
      UUID tenantId, UUID recordId, UUID actor) {
    OperatorCorrectionLearningRecord record = load(tenantId, recordId);
    if (record.getCorrectionType() == OperatorCorrectionType.PRICE_OR_STOCK_CORRECTION_BLOCKED) {
      throw new IllegalArgumentException(
          "Price/stock corrections cannot be approved for authoritative AI learning");
    }
    if (record.getStatus() == OperatorCorrectionStatus.REJECTED) {
      throw new ConflictException("A rejected correction cannot be approved for learning");
    }
    Instant now = clock.instant();
    if (record.getStatus() == OperatorCorrectionStatus.RECORDED) {
      record.approveForLearning(now);
      recordAudit(tenantId, "AI_OPERATOR_CORRECTION_APPROVED", record, actor);
    }
    // Idempotent publish: the projector creates/supersedes governed memory from this event.
    TrustAiDomainEvent event = eventPublisher.publishOnce(tenantId,
        TrustAiEventType.OPERATOR_CORRECTION_RECORDED, AiMemorySourceType.OPERATOR_CORRECTION,
        record.getId(), "operator-correction:" + record.getId(),
        "Operator-approved " + record.getCorrectionType().name() + " correction");
    return new CorrectionLearningProjectionResponse(
        record.getId(), record.getStatus().name(), event.getId(), event.getStatus().name());
  }

  @Transactional
  public OperatorCorrectionLearningRecord rejectCorrection(
      UUID tenantId, UUID recordId, String reason, UUID actor) {
    OperatorCorrectionLearningRecord record = load(tenantId, recordId);
    String trimmedReason = bound(requireText(reason, "reason"), MAX_REASON);
    if (record.getStatus() == OperatorCorrectionStatus.PROJECTED_TO_MEMORY) {
      throw new ConflictException(
          "Correction already projected to memory; invalidate the memory record instead of rejecting");
    }
    if (record.getStatus() == OperatorCorrectionStatus.REJECTED) {
      return record; // idempotent
    }
    record.reject(trimmedReason, clock.instant());
    recordAudit(tenantId, "AI_OPERATOR_CORRECTION_REJECTED", record, actor);
    return record;
  }

  // ----------------------------- read side -----------------------------

  @Transactional(readOnly = true)
  public OperatorCorrectionLearningRecord getCorrection(UUID tenantId, UUID recordId) {
    return load(required(tenantId, "tenantId"), recordId);
  }

  @Transactional(readOnly = true)
  public List<OperatorCorrectionLearningRecord> listCorrections(
      UUID tenantId, OperatorCorrectionStatus status, OperatorCorrectionType correctionType, int page, int size) {
    required(tenantId, "tenantId");
    Pageable pageable = PageRequest.of(Math.max(page, 0), clampLimit(size));
    if (status != null && correctionType != null) {
      return corrections.findByTenantIdAndStatusAndCorrectionTypeOrderByCreatedAtDesc(
          tenantId, status, correctionType, pageable);
    }
    if (status != null) {
      return corrections.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, status, pageable);
    }
    if (correctionType != null) {
      return corrections.findByTenantIdAndCorrectionTypeOrderByCreatedAtDesc(tenantId, correctionType, pageable);
    }
    return corrections.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
  }

  // ----------------------------- helpers -----------------------------

  private OperatorCorrectionLearningRecord load(UUID tenantId, UUID recordId) {
    return corrections.findByIdAndTenantId(required(recordId, "recordId"), tenantId)
        .orElseThrow(() -> new NotFoundException("Operator correction learning record not found"));
  }

  private void recordAudit(UUID tenantId, String action, OperatorCorrectionLearningRecord record, UUID actor) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("correctionId", record.getId().toString());
    metadata.put("correctionType", record.getCorrectionType().name());
    metadata.put("status", record.getStatus().name());
    metadata.put("learningEligible", record.isLearningEligible());
    auditEvents.save(new AuditEvent(tenantId, actor, action, "OperatorCorrectionLearningRecord",
        record.getId().toString(), jsonSupport.writeObject(metadata), clock.instant()));
  }

  /** SHA-256 hex of a raw value. Returns null for null/blank input. The raw input is never stored/logged. */
  static String sha256Hex(String rawValue) {
    if (rawValue == null) {
      return null;
    }
    String trimmed = rawValue.strip();
    if (trimmed.isEmpty()) {
      return null;
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(trimmed.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is required but unavailable", ex);
    }
  }

  private BigDecimal normalizeConfidence(BigDecimal value) {
    if (value == null) {
      throw new IllegalArgumentException("confidence is required (0..1)");
    }
    BigDecimal scaled = value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    if (scaled.signum() < 0 || scaled.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException("confidence must be between 0 and 1");
    }
    return scaled;
  }

  static int clampLimit(int requested) {
    if (requested <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(requested, MAX_LIMIT);
  }

  private static String bound(String value, int max) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.strip();
  }

  private static <T> T required(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }
}
