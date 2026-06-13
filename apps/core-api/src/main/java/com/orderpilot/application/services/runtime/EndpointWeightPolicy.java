package com.orderpilot.application.services.runtime;

import com.orderpilot.domain.usage.UsageMetricType;
import java.util.Map;

/**
 * OP-CAP-16C Quota + Rate Limit Enforcement — the deterministic, code-defined policy table mapping a
 * {@link RuntimeOperationType} to:
 *
 * <ul>
 *   <li>a cost <b>weight</b> (cheap reads = 1; AI / document / bulk / reconciliation are heavier),
 *   <li>a fixed-window <b>rate-limit rule</b> (heavier operations get a stricter effective allowance
 *       because their weight divides into the same-ish budget fewer times), and
 *   <li>a default <b>usage metric</b> a quota policy is evaluated against (some read-only operations
 *       have no quota dimension).
 * </ul>
 *
 * <p>Stage 16C keeps these in code (no table) — see the stage doc. All values are constants, so the
 * policy is fully deterministic and testable.
 */
public final class EndpointWeightPolicy {
  private EndpointWeightPolicy() {}

  private static final long DEFAULT_WINDOW_SECONDS = 60L;

  private static final Map<RuntimeOperationType, Integer> WEIGHTS =
      Map.of(
          RuntimeOperationType.SEARCH_QUERY, 1,
          RuntimeOperationType.CHANNEL_MESSAGE_RECEIVED, 1,
          RuntimeOperationType.AI_ROUTING_DECISION, 2,
          RuntimeOperationType.REPORT_GENERATED, 4,
          RuntimeOperationType.DOCUMENT_UPLOAD, 5,
          RuntimeOperationType.RECONCILIATION_RUN, 6,
          RuntimeOperationType.AI_DOCUMENT_EXTRACTION, 8,
          RuntimeOperationType.BULK_IMPORT, 10);

  // Per-window weighted budget per tenant+operation. Heavier operations carry a smaller budget so
  // their effective call allowance is stricter.
  private static final Map<RuntimeOperationType, Long> WINDOW_BUDGETS =
      Map.of(
          RuntimeOperationType.SEARCH_QUERY, 120L,
          RuntimeOperationType.CHANNEL_MESSAGE_RECEIVED, 120L,
          RuntimeOperationType.AI_ROUTING_DECISION, 120L,
          RuntimeOperationType.REPORT_GENERATED, 60L,
          RuntimeOperationType.DOCUMENT_UPLOAD, 50L,
          RuntimeOperationType.RECONCILIATION_RUN, 60L,
          RuntimeOperationType.AI_DOCUMENT_EXTRACTION, 40L,
          RuntimeOperationType.BULK_IMPORT, 30L);

  // Default quota metric per operation. null → the operation has no quota dimension (allow by NO_POLICY).
  private static final Map<RuntimeOperationType, UsageMetricType> DEFAULT_METRICS =
      Map.of(
          RuntimeOperationType.AI_ROUTING_DECISION, UsageMetricType.AI_INPUT_UNITS,
          RuntimeOperationType.AI_DOCUMENT_EXTRACTION, UsageMetricType.AI_INPUT_UNITS,
          RuntimeOperationType.BULK_IMPORT, UsageMetricType.AI_INPUT_UNITS,
          RuntimeOperationType.DOCUMENT_UPLOAD, UsageMetricType.DOCUMENT_UPLOAD,
          RuntimeOperationType.CHANNEL_MESSAGE_RECEIVED, UsageMetricType.CHANNEL_MESSAGE,
          RuntimeOperationType.RECONCILIATION_RUN, UsageMetricType.RECONCILIATION_RUN);

  /** Cost weight for an operation (defaults to 1 for an unknown/cheap operation). */
  public static int weightFor(RuntimeOperationType operationType) {
    if (operationType == null) {
      return 1;
    }
    return WEIGHTS.getOrDefault(operationType, 1);
  }

  /** Fixed-window rate-limit rule for an operation. */
  public static RateLimitRule ruleFor(RuntimeOperationType operationType) {
    long budget =
        operationType == null ? 120L : WINDOW_BUDGETS.getOrDefault(operationType, 120L);
    return new RateLimitRule(DEFAULT_WINDOW_SECONDS, budget);
  }

  /** Default quota metric for an operation, or {@code null} when it has no quota dimension. */
  public static UsageMetricType defaultMetricFor(RuntimeOperationType operationType) {
    if (operationType == null) {
      return null;
    }
    return DEFAULT_METRICS.get(operationType);
  }
}
