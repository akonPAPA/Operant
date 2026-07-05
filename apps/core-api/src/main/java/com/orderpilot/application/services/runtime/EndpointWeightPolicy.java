package com.orderpilot.application.services.runtime;

import com.orderpilot.domain.usage.UsageMetricType;
import java.util.Map;
import static java.util.Map.entry;

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
      Map.ofEntries(
          entry(RuntimeOperationType.SEARCH_QUERY, 1),
          entry(RuntimeOperationType.CHANNEL_MESSAGE_RECEIVED, 1),
          entry(RuntimeOperationType.AI_ROUTING_DECISION, 2),
          entry(RuntimeOperationType.REPORT_GENERATED, 4),
          entry(RuntimeOperationType.DOCUMENT_UPLOAD, 5),
          entry(RuntimeOperationType.RECONCILIATION_RUN, 6),
          entry(RuntimeOperationType.AI_DOCUMENT_EXTRACTION, 8),
          entry(RuntimeOperationType.BULK_IMPORT, 10),
          // OP-CAP-16G: AI explanation/summary generation is an AI/provider call (heavier than a read,
          // lighter than full document extraction).
          entry(RuntimeOperationType.AI_VALIDATION_EXPLANATION, 4),
          // OP-CAP-27B: RFQ/AI/demo path operator-initiated boundaries — modest weights (deterministic
          // work, not full document/AI extraction) so the demo flow is backpressure-gated but not
          // throttled under normal operator use.
          entry(RuntimeOperationType.DEMO_RFQ_HANDOFF_CREATE, 2),
          entry(RuntimeOperationType.RFQ_HANDOFF_DRAFT_QUOTE_CREATE, 3),
          entry(RuntimeOperationType.RFQ_HANDOFF_DEMO_DECISION, 2));

  // Per-window weighted budget per tenant+operation. Heavier operations carry a smaller budget so
  // their effective call allowance is stricter.
  private static final Map<RuntimeOperationType, Long> WINDOW_BUDGETS =
      Map.ofEntries(
          entry(RuntimeOperationType.SEARCH_QUERY, 120L),
          entry(RuntimeOperationType.CHANNEL_MESSAGE_RECEIVED, 120L),
          entry(RuntimeOperationType.AI_ROUTING_DECISION, 120L),
          entry(RuntimeOperationType.REPORT_GENERATED, 60L),
          entry(RuntimeOperationType.DOCUMENT_UPLOAD, 50L),
          entry(RuntimeOperationType.RECONCILIATION_RUN, 60L),
          entry(RuntimeOperationType.AI_DOCUMENT_EXTRACTION, 40L),
          entry(RuntimeOperationType.BULK_IMPORT, 30L),
          // OP-CAP-16G: AI explanation budget.
          entry(RuntimeOperationType.AI_VALIDATION_EXPLANATION, 60L),
          // OP-CAP-27B: RFQ/AI/demo path budgets.
          entry(RuntimeOperationType.DEMO_RFQ_HANDOFF_CREATE, 60L),
          entry(RuntimeOperationType.RFQ_HANDOFF_DRAFT_QUOTE_CREATE, 60L),
          entry(RuntimeOperationType.RFQ_HANDOFF_DEMO_DECISION, 60L));

  // Default quota metric per operation. null → the operation has no quota dimension (allow by NO_POLICY).
  private static final Map<RuntimeOperationType, UsageMetricType> DEFAULT_METRICS =
      Map.of(
          RuntimeOperationType.AI_ROUTING_DECISION, UsageMetricType.AI_INPUT_UNITS,
          RuntimeOperationType.AI_DOCUMENT_EXTRACTION, UsageMetricType.AI_INPUT_UNITS,
          RuntimeOperationType.BULK_IMPORT, UsageMetricType.AI_INPUT_UNITS,
          RuntimeOperationType.DOCUMENT_UPLOAD, UsageMetricType.DOCUMENT_UPLOAD,
          RuntimeOperationType.CHANNEL_MESSAGE_RECEIVED, UsageMetricType.CHANNEL_MESSAGE,
          RuntimeOperationType.RECONCILIATION_RUN, UsageMetricType.RECONCILIATION_RUN,
          // OP-CAP-16G: AI explanation consumes the same AI input units metric as extraction.
          RuntimeOperationType.AI_VALIDATION_EXPLANATION, UsageMetricType.AI_INPUT_UNITS,
          // OP-CAP-16G: report/export generation has its own quota dimension.
          RuntimeOperationType.REPORT_GENERATED, UsageMetricType.REPORT_GENERATED);

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
