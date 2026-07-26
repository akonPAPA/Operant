package com.orderpilot.application.services.trust;

import com.orderpilot.application.services.trust.AiMemoryGovernanceService.CreateMemoryCommand;
import com.orderpilot.application.services.trust.AiMemoryGovernanceService.EvidenceSpec;
import com.orderpilot.application.services.trust.AiMemoryGovernanceService.SupersedeMemoryCommand;
import com.orderpilot.domain.trust.ai.AiMemoryAuthorityLevel;
import com.orderpilot.domain.trust.ai.AiMemoryEvidenceType;
import com.orderpilot.domain.trust.ai.AiMemoryNamespace;
import com.orderpilot.domain.trust.ai.AiMemoryRecord;
import com.orderpilot.domain.trust.ai.AiMemorySourceType;
import com.orderpilot.domain.trust.ai.AiMemoryType;
import com.orderpilot.domain.trust.events.TrustAiDomainEvent;
import com.orderpilot.domain.trust.learning.OperatorCorrectionLearningRecord;
import com.orderpilot.domain.trust.learning.OperatorCorrectionLearningRecordRepository;
import com.orderpilot.domain.trust.learning.OperatorCorrectionStatus;
import com.orderpilot.domain.trust.learning.OperatorCorrectionType;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * OP-CAP-18 Trust/AI Event Projector Runtime — the AI memory projector.
 *
 * Converts approved, sanitized internal events into advisory, low-authority OP-CAP-17F AI memory via the
 * {@link AiMemoryGovernanceService} (it never bypasses that governance service or the
 * {@link AiMemoryPolicyService} sanitization). It NEVER creates/approves orders, quotes, payments, trust
 * decisions, ERP/PSP writes, or any authoritative business state — those remain with the deterministic
 * command services. Operator-approved corrections may become {@code HUMAN_APPROVED} memory; purely
 * automated events produce only {@code MEDIUM}/{@code SYSTEM_DERIVED} advisory hints. Price/stock
 * corrections never become authoritative memory. The runtime guarantees idempotency via checkpoints.
 */
@Service
public class AiMemoryEventProjector {
  public static final String PROJECTOR_NAME = "AiMemoryEventProjector";
  static final int MAX_KEY = 160;
  /** Conservative default confidence for automated advisory hints (above the usable floor). */
  static final BigDecimal AUTOMATED_HINT_CONFIDENCE = new BigDecimal("0.60");

  private final AiMemoryGovernanceService memory;
  private final AiMemoryPolicyService policy;
  private final OperatorCorrectionLearningRecordRepository corrections;
  private final Clock clock;

  public AiMemoryEventProjector(AiMemoryGovernanceService memory, AiMemoryPolicyService policy,
      OperatorCorrectionLearningRecordRepository corrections, Clock clock) {
    this.memory = memory;
    this.policy = policy;
    this.corrections = corrections;
    this.clock = clock;
  }

  /** Outcome of projecting one event (no business-state mutation, ever). */
  public record ProjectionOutcome(Kind kind, String projectedRecordType, UUID projectedRecordId) {
    public enum Kind { PROJECTED, ACKNOWLEDGED, SKIPPED }

    public static ProjectionOutcome projected(String type, UUID id) {
      return new ProjectionOutcome(Kind.PROJECTED, type, id);
    }

    public static ProjectionOutcome acknowledged() {
      return new ProjectionOutcome(Kind.ACKNOWLEDGED, null, null);
    }

    public static ProjectionOutcome skipped() {
      return new ProjectionOutcome(Kind.SKIPPED, null, null);
    }
  }

  /**
   * Projects one event. Runs inside the runtime's per-event transaction. Returns the outcome; throws only
   * on unexpected failure (the runtime turns that into a bounded FAILED/DEAD_LETTERED state).
   */
  public ProjectionOutcome project(TrustAiDomainEvent event) {
    return switch (event.getEventType()) {
      case OPERATOR_CORRECTION_RECORDED -> projectOperatorCorrection(event);
      case TRUST_RISK_DECIDED -> projectAdvisoryHint(event, AiMemoryNamespace.TRUST_SIGNAL_HINT,
          AiMemoryType.HINT, AiMemoryAuthorityLevel.MEDIUM, "trust-signal");
      case PAYMENT_OBLIGATION_UPDATED, PAYMENT_ALLOCATION_RECORDED -> projectAdvisoryHint(event,
          AiMemoryNamespace.PAYMENT_MATCH_HINT, AiMemoryType.HINT, AiMemoryAuthorityLevel.MEDIUM,
          "payment-match");
      case DOCUMENT_TRUST_COMPLETED -> projectAdvisoryHint(event, AiMemoryNamespace.DOCUMENT_TEMPLATE,
          AiMemoryType.TEMPLATE, AiMemoryAuthorityLevel.SYSTEM_DERIVED, "document-template");
      // Invalidation/trace events are acknowledged only — they must NOT (re)create memory.
      case AI_MEMORY_INVALIDATED, AI_RUNTIME_TRACE_RECORDED -> ProjectionOutcome.acknowledged();
      // Not projected in this stage (no safe deterministic memory mapping yet).
      case COUNTERPARTY_TRUST_UPDATED, TRUST_RISK_OVERRIDDEN -> ProjectionOutcome.skipped();
    };
  }

  // ----------------------------- operator correction projection -----------------------------

  private ProjectionOutcome projectOperatorCorrection(TrustAiDomainEvent event) {
    if (event.getSourceId() == null) {
      return ProjectionOutcome.skipped();
    }
    Optional<OperatorCorrectionLearningRecord> found =
        corrections.findByIdAndTenantId(event.getSourceId(), event.getTenantId());
    if (found.isEmpty()) {
      return ProjectionOutcome.skipped();
    }
    OperatorCorrectionLearningRecord correction = found.get();
    // Only operator-approved, learning-eligible corrections become memory; price/stock never authoritative.
    if (correction.getStatus() != OperatorCorrectionStatus.APPROVED_FOR_LEARNING
        || !correction.isLearningEligible()
        || correction.getCorrectionType() == OperatorCorrectionType.PRICE_OR_STOCK_CORRECTION_BLOCKED) {
      return ProjectionOutcome.skipped();
    }
    AiMemoryNamespace namespace = namespaceFor(correction.getCorrectionType());
    if (namespace == null) {
      return ProjectionOutcome.skipped();
    }

    String keyBasis = correction.getCorrectedValueHash() != null ? correction.getCorrectedValueHash()
        : correction.getNormalizedCorrection() != null ? correction.getNormalizedCorrection()
        : correction.getId().toString();
    String fieldPart = correction.getFieldKey() != null ? correction.getFieldKey() + ":" : "";
    String memoryKey = bound(namespacePrefix(correction.getCorrectionType()) + ":" + fieldPart + keyBasis, MAX_KEY);
    String title = bound("Operator correction: " + correction.getCorrectionType().name(), 160);
    EvidenceSpec evidence = new EvidenceSpec(AiMemoryEvidenceType.CORRECTION,
        "operator-correction:" + correction.getId(), AiMemorySourceType.OPERATOR_CORRECTION,
        correction.getId(), correction.getFieldKey(), correction.getConfidence());

    UUID memoryId = upsertMemory(correction.getTenantId(), namespace, memoryKey, AiMemoryType.CORRECTION,
        AiMemoryAuthorityLevel.HUMAN_APPROVED, AiMemorySourceType.OPERATOR_CORRECTION, correction.getId(),
        "operator-correction:" + correction.getId(), title, correction.getCorrectionSummary(),
        correction.getNormalizedCorrection(), correction.getConfidence(), List.of(evidence));

    correction.markProjected(memoryId, clock.instant());
    corrections.save(correction);
    return ProjectionOutcome.projected("AiMemoryRecord", memoryId);
  }

  // ----------------------------- automated advisory hint projection -----------------------------

  private ProjectionOutcome projectAdvisoryHint(TrustAiDomainEvent event, AiMemoryNamespace namespace,
      AiMemoryType memoryType, AiMemoryAuthorityLevel authority, String keyPrefix) {
    // Only project from a bounded, sanitized summary; otherwise there is nothing safe/useful to store.
    if (event.getPayloadSummary() == null || event.getPayloadSummary().isBlank()) {
      return ProjectionOutcome.skipped();
    }
    UUID keyId = event.getSubjectId() != null ? event.getSubjectId() : event.getSourceId();
    if (keyId == null) {
      return ProjectionOutcome.skipped();
    }
    String memoryKey = bound(keyPrefix + ":" + keyId, MAX_KEY);
    String title = bound(humanize(keyPrefix) + " hint", 160);
    UUID memoryId = upsertMemory(event.getTenantId(), namespace, memoryKey, memoryType, authority,
        event.getSourceType(), event.getSourceId(), keyPrefix + ":" + keyId, title,
        event.getPayloadSummary(), null, AUTOMATED_HINT_CONFIDENCE, List.of());
    return ProjectionOutcome.projected("AiMemoryRecord", memoryId);
  }

  // ----------------------------- shared upsert (idempotent via 17F governance) -----------------------------

  private UUID upsertMemory(UUID tenantId, AiMemoryNamespace namespace, String memoryKey,
      AiMemoryType memoryType, AiMemoryAuthorityLevel authority, AiMemorySourceType sourceType, UUID sourceId,
      String sourceRef, String title, String summary, String normalizedValue, BigDecimal confidence,
      List<EvidenceSpec> evidence) {
    // Explicit sanitization gate before any write (governance re-validates as defence in depth).
    policy.validateMemoryPayload(title, summary, normalizedValue, sourceRef);
    Optional<AiMemoryRecord> existing = memory.findByMemoryKey(tenantId, namespace, memoryKey);
    if (existing.isPresent()) {
      AiMemoryRecord superseded = memory.supersedeMemoryRecord(new SupersedeMemoryCommand(
          tenantId, existing.get().getId(), memoryType, authority, title, summary, normalizedValue,
          confidence, 1, null, "Superseded by projector from internal event", null));
      return superseded.getId();
    }
    AiMemoryRecord created = memory.createMemoryRecord(new CreateMemoryCommand(
        tenantId, namespace, memoryKey, memoryType, authority, sourceType, sourceId, sourceRef, title,
        summary, normalizedValue, confidence, 1, null, evidence, null));
    return created.getId();
  }

  // ----------------------------- mappings / helpers -----------------------------

  static AiMemoryNamespace namespaceFor(OperatorCorrectionType type) {
    return switch (type) {
      case PRODUCT_ALIAS -> AiMemoryNamespace.PRODUCT_ALIAS_HINT;
      case CUSTOMER_ALIAS -> AiMemoryNamespace.COUNTERPARTY_PATTERN;
      case DOCUMENT_FIELD_MAPPING -> AiMemoryNamespace.EXTRACTION_CORRECTION;
      case PAYMENT_MATCHING_HINT -> AiMemoryNamespace.PAYMENT_MATCH_HINT;
      case TRUST_REASON_RECLASSIFICATION -> AiMemoryNamespace.TRUST_SIGNAL_HINT;
      case VALIDATION_RULE_CLARIFICATION -> AiMemoryNamespace.VALIDATION_EXPLANATION;
      case BOT_RESPONSE_CORRECTION -> AiMemoryNamespace.BOT_CONVERSATION_SUMMARY;
      case IMPORT_MAPPING_CORRECTION -> AiMemoryNamespace.OPERATOR_CORRECTION_PATTERN;
      case PRICE_OR_STOCK_CORRECTION_BLOCKED -> null;
    };
  }

  private static String namespacePrefix(OperatorCorrectionType type) {
    return switch (type) {
      case PRODUCT_ALIAS -> "product-alias";
      case CUSTOMER_ALIAS -> "customer-alias";
      case DOCUMENT_FIELD_MAPPING -> "document-field";
      case PAYMENT_MATCHING_HINT -> "payment-match";
      case TRUST_REASON_RECLASSIFICATION -> "trust-signal";
      case VALIDATION_RULE_CLARIFICATION -> "validation-explanation";
      case BOT_RESPONSE_CORRECTION -> "bot-summary";
      case IMPORT_MAPPING_CORRECTION -> "import-mapping";
      case PRICE_OR_STOCK_CORRECTION_BLOCKED -> "blocked";
    };
  }

  private static String humanize(String key) {
    String spaced = key.replace('-', ' ');
    return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
  }

  private static String bound(String value, int max) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }
}
