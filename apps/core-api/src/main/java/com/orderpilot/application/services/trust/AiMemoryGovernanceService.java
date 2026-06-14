package com.orderpilot.application.services.trust;

import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.common.errors.ConflictException;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.domain.audit.AuditEvent;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.trust.ai.AiMemoryActorType;
import com.orderpilot.domain.trust.ai.AiMemoryAuthorityLevel;
import com.orderpilot.domain.trust.ai.AiMemoryEvidenceRef;
import com.orderpilot.domain.trust.ai.AiMemoryEvidenceRefRepository;
import com.orderpilot.domain.trust.ai.AiMemoryEvidenceType;
import com.orderpilot.domain.trust.ai.AiMemoryInvalidationEvent;
import com.orderpilot.domain.trust.ai.AiMemoryInvalidationEventRepository;
import com.orderpilot.domain.trust.ai.AiMemoryInvalidationReasonCode;
import com.orderpilot.domain.trust.ai.AiMemoryNamespace;
import com.orderpilot.domain.trust.ai.AiMemoryRecord;
import com.orderpilot.domain.trust.ai.AiMemoryRecordRepository;
import com.orderpilot.domain.trust.ai.AiMemorySourceType;
import com.orderpilot.domain.trust.ai.AiMemoryStatus;
import com.orderpilot.domain.trust.ai.AiMemoryType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-17F AI Data Runtime / Tenant-Scoped AI Memory Governance — governance command/query service.
 *
 * Stores and governs bounded, sanitized, tenant-scoped advisory AI memory derived from approved
 * OrderPilot workflow events. It is NOT model training, NOT a global memory, and NOT raw prompt/document
 * storage. Every row is tenant-scoped; every query is tenant-isolated and bounded. Payloads are sanitized
 * by {@link AiMemoryPolicyService} before persistence. Memory is advisory and low-authority — it never
 * becomes the source of truth, and AI never autonomously writes business data. Important create/supersede/
 * invalidate/expire actions emit an {@link AuditEvent}.
 */
@Service
public class AiMemoryGovernanceService {
  public static final int DEFAULT_LIMIT = 25;
  static final int MAX_LIMIT = 100;
  static final int EXPIRE_SCAN_LIMIT = 200;
  static final int CONFIDENCE_SCALE = 4;

  private final AiMemoryRecordRepository records;
  private final AiMemoryEvidenceRefRepository evidenceRefs;
  private final AiMemoryInvalidationEventRepository invalidationEvents;
  private final AiMemoryPolicyService policy;
  private final AuditEventRepository auditEvents;
  private final JsonSupport jsonSupport;
  private Clock clock;

  public AiMemoryGovernanceService(
      AiMemoryRecordRepository records,
      AiMemoryEvidenceRefRepository evidenceRefs,
      AiMemoryInvalidationEventRepository invalidationEvents,
      AiMemoryPolicyService policy,
      AuditEventRepository auditEvents,
      JsonSupport jsonSupport,
      Clock clock) {
    this.records = records;
    this.evidenceRefs = evidenceRefs;
    this.invalidationEvents = invalidationEvents;
    this.policy = policy;
    this.auditEvents = auditEvents;
    this.jsonSupport = jsonSupport;
    this.clock = clock;
  }

  // ----------------------------- command records -----------------------------

  public record EvidenceSpec(
      AiMemoryEvidenceType evidenceType, String evidenceRef, AiMemorySourceType sourceType, UUID sourceId,
      String fieldKey, BigDecimal confidence) {}

  public record CreateMemoryCommand(
      UUID tenantId, AiMemoryNamespace namespace, String memoryKey, AiMemoryType memoryType,
      AiMemoryAuthorityLevel authorityLevel, AiMemorySourceType sourceType, UUID sourceId, String sourceRef,
      String title, String summary, String normalizedValue, BigDecimal confidence, Integer weight,
      Long ttlSeconds, List<EvidenceSpec> evidence, UUID createdBy) {}

  public record SupersedeMemoryCommand(
      UUID tenantId, UUID recordId, AiMemoryType memoryType, AiMemoryAuthorityLevel authorityLevel,
      String title, String summary, String normalizedValue, BigDecimal confidence, Integer weight,
      Long ttlSeconds, String reason, UUID actor) {}

  // ----------------------------- create -----------------------------

  @Transactional
  public AiMemoryRecord createMemoryRecord(CreateMemoryCommand cmd) {
    UUID tenantId = required(cmd.tenantId(), "tenantId");
    AiMemoryNamespace namespace = required(cmd.namespace(), "namespace");
    AiMemoryType memoryType = required(cmd.memoryType(), "memoryType");
    AiMemoryAuthorityLevel authorityLevel = required(cmd.authorityLevel(), "authorityLevel");
    AiMemorySourceType sourceType = required(cmd.sourceType(), "sourceType");
    String memoryKey = requireText(cmd.memoryKey(), "memoryKey");
    String title = requireText(cmd.title(), "title");
    String summary = requireText(cmd.summary(), "summary");
    String normalizedValue = trimToNull(cmd.normalizedValue());
    String sourceRef = trimToNull(cmd.sourceRef());

    // Conservative sanitization BEFORE persistence — rejects over-length and raw-prompt/secret markers.
    policy.validateMemoryPayload(title, summary, normalizedValue, sourceRef);
    BigDecimal confidence = normalizeConfidence(cmd.confidence());
    int weight = normalizeWeight(cmd.weight());

    // Idempotency/duplication: at most one ACTIVE version per (tenant, namespace, key) at a time.
    Optional<AiMemoryRecord> active = records
        .findFirstByTenantIdAndNamespaceAndMemoryKeyAndStatusOrderByVersionDesc(
            tenantId, namespace, memoryKey, AiMemoryStatus.ACTIVE);
    if (active.isPresent()) {
      throw new ConflictException(
          "An active AI memory record already exists for this namespace/key; supersede it instead");
    }
    int version = records.findFirstByTenantIdAndNamespaceAndMemoryKeyOrderByVersionDesc(
        tenantId, namespace, memoryKey).map(r -> r.getVersion() + 1).orElse(1);

    Instant now = clock.instant();
    Instant expiresAt = computeExpiry(cmd.ttlSeconds(), now);
    AiMemoryRecord record = records.save(new AiMemoryRecord(
        tenantId, namespace, memoryKey, memoryType, authorityLevel, sourceType, cmd.sourceId(), sourceRef,
        title, summary, normalizedValue, confidence, weight, version, expiresAt, cmd.createdBy(), now));

    if (cmd.evidence() != null) {
      for (EvidenceSpec spec : cmd.evidence()) {
        if (spec == null || spec.evidenceType() == null || spec.evidenceRef() == null
            || spec.evidenceRef().isBlank()) {
          continue;
        }
        evidenceRefs.save(new AiMemoryEvidenceRef(tenantId, record.getId(), spec.evidenceType(),
            bound(spec.evidenceRef(), 160), spec.sourceType(), spec.sourceId(), bound(spec.fieldKey(), 64),
            spec.confidence() == null ? null : clampConfidence(spec.confidence()), now));
      }
    }
    recordAudit(tenantId, "AI_MEMORY_RECORD_CREATED", record, cmd.createdBy());
    return record;
  }

  // ----------------------------- supersede -----------------------------

  @Transactional
  public AiMemoryRecord supersedeMemoryRecord(SupersedeMemoryCommand cmd) {
    UUID tenantId = required(cmd.tenantId(), "tenantId");
    AiMemoryRecord current = load(tenantId, cmd.recordId());
    if (current.getStatus() != AiMemoryStatus.ACTIVE) {
      throw new ConflictException("Only an ACTIVE memory record can be superseded");
    }
    AiMemoryType memoryType = cmd.memoryType() != null ? cmd.memoryType() : current.getMemoryType();
    AiMemoryAuthorityLevel authorityLevel =
        cmd.authorityLevel() != null ? cmd.authorityLevel() : current.getAuthorityLevel();
    String title = requireText(cmd.title(), "title");
    String summary = requireText(cmd.summary(), "summary");
    String normalizedValue = trimToNull(cmd.normalizedValue());
    policy.validateMemoryPayload(title, summary, normalizedValue, current.getSourceRef());
    BigDecimal confidence = normalizeConfidence(cmd.confidence());
    int weight = normalizeWeight(cmd.weight());

    Instant now = clock.instant();
    AiMemoryStatus prevStatus = current.getStatus();
    current.markSuperseded(now);
    appendInvalidationEvent(current, prevStatus, AiMemoryStatus.SUPERSEDED,
        AiMemoryInvalidationReasonCode.SUPERSEDED_BY_NEW_VERSION, bound(cmd.reason(), 280),
        AiMemoryActorType.OPERATOR, cmd.actor(), now);

    AiMemoryRecord next = records.save(new AiMemoryRecord(
        tenantId, current.getNamespace(), current.getMemoryKey(), memoryType, authorityLevel,
        current.getSourceType(), current.getSourceId(), current.getSourceRef(), title, summary,
        normalizedValue, confidence, weight, current.getVersion() + 1,
        computeExpiry(cmd.ttlSeconds(), now), cmd.actor(), now));
    recordAudit(tenantId, "AI_MEMORY_RECORD_SUPERSEDED", next, cmd.actor());
    return next;
  }

  // ----------------------------- invalidate / expire -----------------------------

  @Transactional
  public AiMemoryRecord invalidateMemoryRecord(UUID tenantId, UUID recordId,
      AiMemoryInvalidationReasonCode reasonCode, String reason, AiMemoryActorType actorType, UUID actorId) {
    required(tenantId, "tenantId");
    AiMemoryInvalidationReasonCode code = required(reasonCode, "reasonCode");
    String trimmedReason = requireText(reason, "reason");
    AiMemoryRecord record = load(tenantId, recordId);
    if (record.getStatus() == AiMemoryStatus.INVALIDATED) {
      return record; // idempotent
    }
    Instant now = clock.instant();
    AiMemoryStatus prevStatus = record.getStatus();
    record.markInvalidated(bound(trimmedReason, 280), now);
    appendInvalidationEvent(record, prevStatus, AiMemoryStatus.INVALIDATED, code, bound(trimmedReason, 280),
        actorType != null ? actorType : AiMemoryActorType.OPERATOR, actorId, now);
    recordAudit(tenantId, "AI_MEMORY_RECORD_INVALIDATED", record, actorId);
    return record;
  }

  /** Bounded sweep: flips ACTIVE records past their TTL to EXPIRED. Returns the number expired. */
  @Transactional
  public int expireDueRecords(UUID tenantId, Instant now) {
    required(tenantId, "tenantId");
    Instant effectiveNow = now != null ? now : clock.instant();
    List<AiMemoryRecord> due = records.findByTenantIdAndStatusAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
        tenantId, AiMemoryStatus.ACTIVE, effectiveNow, PageRequest.of(0, EXPIRE_SCAN_LIMIT));
    for (AiMemoryRecord record : due) {
      AiMemoryStatus prevStatus = record.getStatus();
      record.markExpired(effectiveNow);
      appendInvalidationEvent(record, prevStatus, AiMemoryStatus.EXPIRED,
          AiMemoryInvalidationReasonCode.EXPIRED, "TTL elapsed", AiMemoryActorType.SYSTEM, null, effectiveNow);
    }
    return due.size();
  }

  // ----------------------------- read side -----------------------------

  @Transactional(readOnly = true)
  public AiMemoryRecord getRecord(UUID tenantId, UUID recordId) {
    return load(required(tenantId, "tenantId"), recordId);
  }

  @Transactional(readOnly = true)
  public Optional<AiMemoryRecord> findByMemoryKey(UUID tenantId, AiMemoryNamespace namespace, String memoryKey) {
    return records.findFirstByTenantIdAndNamespaceAndMemoryKeyAndStatusOrderByVersionDesc(
        required(tenantId, "tenantId"), required(namespace, "namespace"), requireText(memoryKey, "memoryKey"),
        AiMemoryStatus.ACTIVE);
  }

  @Transactional(readOnly = true)
  public List<AiMemoryRecord> searchMemory(UUID tenantId, AiMemoryNamespace namespace, String memoryKey,
      boolean includeExpired, boolean includeLowConfidence, int limit) {
    required(tenantId, "tenantId");
    required(namespace, "namespace");
    Pageable pageable = PageRequest.of(0, clampLimit(limit));
    return records.search(tenantId, namespace, AiMemoryStatus.ACTIVE, includeExpired, includeLowConfidence,
        AiMemoryPolicyService.MIN_USABLE_CONFIDENCE, trimToNull(memoryKey), clock.instant(), pageable);
  }

  /** Bounded advisory read: increments the access counter and serves the record (tenant-scoped). */
  @Transactional
  public AiMemoryRecord recordMemoryAccess(UUID tenantId, UUID recordId) {
    AiMemoryRecord record = load(required(tenantId, "tenantId"), recordId);
    record.recordAccess(clock.instant());
    return record;
  }

  @Transactional(readOnly = true)
  public List<AiMemoryEvidenceRef> listEvidence(UUID tenantId, UUID recordId) {
    load(required(tenantId, "tenantId"), recordId); // tenant-scoped existence guard
    return evidenceRefs.findByTenantIdAndAiMemoryRecordIdOrderByCreatedAtAsc(tenantId, recordId);
  }

  @Transactional(readOnly = true)
  public List<AiMemoryInvalidationEvent> listInvalidations(UUID tenantId, UUID recordId) {
    load(required(tenantId, "tenantId"), recordId); // tenant-scoped existence guard
    return invalidationEvents.findByTenantIdAndAiMemoryRecordIdOrderByCreatedAtDesc(tenantId, recordId);
  }

  // ----------------------------- helpers -----------------------------

  private AiMemoryRecord load(UUID tenantId, UUID recordId) {
    return records.findByIdAndTenantId(required(recordId, "recordId"), tenantId)
        .orElseThrow(() -> new NotFoundException("AI memory record not found"));
  }

  private void appendInvalidationEvent(AiMemoryRecord record, AiMemoryStatus prev, AiMemoryStatus next,
      AiMemoryInvalidationReasonCode reasonCode, String reason, AiMemoryActorType actorType, UUID actorId,
      Instant now) {
    invalidationEvents.save(new AiMemoryInvalidationEvent(
        record.getTenantId(), record.getId(), prev, next, reasonCode, reason, actorType, actorId, now));
  }

  private void recordAudit(UUID tenantId, String action, AiMemoryRecord record, UUID actor) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("recordId", record.getId().toString());
    metadata.put("namespace", record.getNamespace().name());
    metadata.put("memoryKey", record.getMemoryKey());
    metadata.put("status", record.getStatus().name());
    metadata.put("authorityLevel", record.getAuthorityLevel().name());
    metadata.put("version", record.getVersion());
    auditEvents.save(new AuditEvent(tenantId, actor, action, "AiMemoryRecord",
        record.getId().toString(), jsonSupport.writeObject(metadata), clock.instant()));
  }

  private Instant computeExpiry(Long ttlSeconds, Instant now) {
    if (ttlSeconds == null) {
      return null;
    }
    if (ttlSeconds <= 0) {
      throw new IllegalArgumentException("ttlSeconds must be positive when provided");
    }
    return now.plusSeconds(ttlSeconds);
  }

  private BigDecimal normalizeConfidence(BigDecimal value) {
    if (value == null) {
      throw new IllegalArgumentException("confidence is required (0..1)");
    }
    return clampConfidence(value);
  }

  private static BigDecimal clampConfidence(BigDecimal value) {
    BigDecimal scaled = value.setScale(CONFIDENCE_SCALE, RoundingMode.HALF_UP);
    if (scaled.signum() < 0 || scaled.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException("confidence must be between 0 and 1");
    }
    return scaled;
  }

  private static int normalizeWeight(Integer weight) {
    if (weight == null) {
      return 1;
    }
    if (weight < 0) {
      throw new IllegalArgumentException("weight must not be negative");
    }
    return weight;
  }

  static int clampLimit(int requested) {
    if (requested <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(requested, MAX_LIMIT);
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String requireText(String value, String field) {
    String trimmed = trimToNull(value);
    if (trimmed == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    return trimmed;
  }

  private static String bound(String value, int max) {
    String trimmed = trimToNull(value);
    if (trimmed == null) {
      return null;
    }
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }

  private static <T> T required(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }
}
