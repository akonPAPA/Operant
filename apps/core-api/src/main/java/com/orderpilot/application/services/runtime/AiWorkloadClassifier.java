package com.orderpilot.application.services.runtime;

import org.springframework.stereotype.Service;

/**
 * Deterministic, side-effect-free classifier that decides how an AI/OCR/heavy workload should be
 * routed <em>before</em> any expensive processing runs.
 *
 * <p>Stage 16A foundation. It does NOT call AI, does NOT touch the database, does NOT call external
 * services, does NOT enforce quotas/rate limits/billing, and does NOT change any existing extraction
 * or business write path. It only produces advisory {@link AiRoutingDecision} metadata that later
 * stages (16B usage metering, 16C quotas, 16D job routing) can build on.
 *
 * <p>Safety posture: suspicious prompt-injection signals and unknown workloads route to human
 * review; large and bulk inputs require async handling. The classifier never logs or returns raw
 * input text — only measurements and stable reason codes.
 */
@Service
public final class AiWorkloadClassifier {

  /** ~4 characters per estimated input unit (token-like). Deterministic, not a billing figure. */
  private static final int CHARS_PER_UNIT = 4;

  private static final int UNITS_PER_PAGE = 300;
  private static final int UNITS_PER_ATTACHMENT = 200;

  private static final int BULK_PAGE_THRESHOLD = 100;
  private static final int BULK_ATTACHMENT_THRESHOLD = 20;
  private static final int BULK_UNIT_THRESHOLD = 50_000;

  private static final int LARGE_PAGE_THRESHOLD = 6;
  private static final int LARGE_UNIT_THRESHOLD = 8_000;

  private static final int MEDIUM_UNIT_THRESHOLD = 1_000;

  /**
   * Classify a workload into a deterministic routing decision. Same input always yields the same
   * decision. Never throws on null/negative inputs; they are normalized.
   */
  public AiRoutingDecision classify(AiWorkloadClassificationRequest request) {
    AiWorkloadEstimate estimate = estimate(request);
    AiWorkloadType type = request == null || request.requestedType() == null
        ? AiWorkloadType.UNKNOWN
        : request.requestedType();
    WorkloadSize size = sizeOf(estimate);
    int units = estimate.estimatedInputUnits();

    // 1. Safety first: suspicious prompt-injection always gates to human review.
    if (estimate.suspiciousPromptInjectionSignal()) {
      return new AiRoutingDecision(
          type, size, ModelTier.HUMAN_REVIEW, false, true, units,
          AiWorkloadReasonCodes.SUSPICIOUS_PROMPT_INJECTION_REVIEW);
    }

    // 2. Empty input: nothing to process, deterministic rules-only default.
    if (isEmptyInput(estimate)) {
      return new AiRoutingDecision(
          type, WorkloadSize.SMALL, ModelTier.RULES_ONLY, false, false, units,
          AiWorkloadReasonCodes.EMPTY_INPUT_RULES_ONLY);
    }

    // 3. Unknown workload type: route to human review as a safe default.
    if (type == AiWorkloadType.UNKNOWN) {
      return new AiRoutingDecision(
          type, size, ModelTier.HUMAN_REVIEW, false, true, units,
          AiWorkloadReasonCodes.UNKNOWN_WORKLOAD_REVIEW);
    }

    // 4. Structured deterministic lookup (price/availability/product match by SKU/identifier).
    if (estimate.structuredIdentifierPresent() && isStructuredLookup(type) && size == WorkloadSize.SMALL) {
      return new AiRoutingDecision(
          type, WorkloadSize.SMALL, ModelTier.RULES_ONLY, false, false, units,
          AiWorkloadReasonCodes.STRUCTURED_IDENTIFIER_RULES_PATH);
    }

    // 5. Bulk inputs require async and human review.
    if (size == WorkloadSize.BULK) {
      return new AiRoutingDecision(
          type, size, ModelTier.HUMAN_REVIEW, true, true, units,
          AiWorkloadReasonCodes.BULK_REQUIRES_REVIEW);
    }

    // 6. Large documents require async large-model processing.
    if (size == WorkloadSize.LARGE) {
      return new AiRoutingDecision(
          type, size, ModelTier.LARGE, true, false, units,
          AiWorkloadReasonCodes.LARGE_DOCUMENT_ASYNC);
    }

    // 7. Medium documents require async medium-model processing.
    if (size == WorkloadSize.MEDIUM) {
      return new AiRoutingDecision(
          type, size, ModelTier.MEDIUM, true, false, units,
          AiWorkloadReasonCodes.MEDIUM_DOCUMENT_ASYNC);
    }

    // 8. Small chat intent: lightweight local classifier, synchronous.
    if (type == AiWorkloadType.CHAT_INTENT) {
      return new AiRoutingDecision(
          type, WorkloadSize.SMALL, ModelTier.SMALL_LOCAL, false, false, units,
          AiWorkloadReasonCodes.SHORT_CHAT_INTENT);
    }

    // 9. Any other small workload: synchronous small-local default.
    return new AiRoutingDecision(
        type, WorkloadSize.SMALL, ModelTier.SMALL_LOCAL, false, false, units,
        AiWorkloadReasonCodes.SMALL_WORKLOAD_LOCAL);
  }

  /** Derive normalized, non-negative measurement signals from the request. */
  public AiWorkloadEstimate estimate(AiWorkloadClassificationRequest request) {
    String text = request == null || request.text() == null ? "" : request.text();
    int textLength = text.length();
    int pageCount = request == null ? 0 : Math.max(0, request.pageCount());
    int attachmentCount = request == null ? 0 : Math.max(0, request.attachmentCount());
    boolean structured = request != null && request.hasStructuredSkuOrIdentifier();
    boolean suspicious = request != null && request.suspiciousPromptInjectionSignal();

    int units = (textLength + CHARS_PER_UNIT - 1) / CHARS_PER_UNIT
        + pageCount * UNITS_PER_PAGE
        + attachmentCount * UNITS_PER_ATTACHMENT;

    boolean bulkLike = pageCount >= BULK_PAGE_THRESHOLD
        || attachmentCount >= BULK_ATTACHMENT_THRESHOLD
        || units >= BULK_UNIT_THRESHOLD;

    return new AiWorkloadEstimate(
        textLength, pageCount, attachmentCount, units, suspicious, structured, bulkLike);
  }

  private static WorkloadSize sizeOf(AiWorkloadEstimate estimate) {
    if (estimate.bulkLike()) {
      return WorkloadSize.BULK;
    }
    if (estimate.pageCount() >= LARGE_PAGE_THRESHOLD
        || estimate.estimatedInputUnits() >= LARGE_UNIT_THRESHOLD) {
      return WorkloadSize.LARGE;
    }
    if (estimate.pageCount() >= 1
        || estimate.attachmentCount() >= 1
        || estimate.estimatedInputUnits() >= MEDIUM_UNIT_THRESHOLD) {
      return WorkloadSize.MEDIUM;
    }
    return WorkloadSize.SMALL;
  }

  private static boolean isEmptyInput(AiWorkloadEstimate estimate) {
    return estimate.textLength() == 0
        && estimate.pageCount() == 0
        && estimate.attachmentCount() == 0;
  }

  private static boolean isStructuredLookup(AiWorkloadType type) {
    return type == AiWorkloadType.PRICE_REQUEST
        || type == AiWorkloadType.AVAILABILITY_REQUEST
        || type == AiWorkloadType.PRODUCT_MATCHING;
  }
}
