package com.orderpilot.domain.trust.ai;

/**
 * OP-CAP-20 Layer A — AI Advisory Runtime Assist.
 *
 * The concrete workflow context an operator (or backend workflow) is asking advisory hints for. Each
 * context type maps deterministically to a default {@link AiAdvisoryTaskType} so runtime assist never has
 * to guess which bounded, tenant-scoped namespaces are relevant. A context is purely an advisory lens —
 * it never identifies a business record that runtime assist mutates, and the assist surface never loads,
 * approves, exports, resolves, or writes business state for the context.
 */
public enum RuntimeAssistContextType {
  /**
   * Trust / validation review runtime assist — helps an operator reviewing a validation/trust outcome see
   * which past governed memory may explain the signal. Maps to {@link AiAdvisoryTaskType#TRUST_SIGNAL_EXPLANATION}.
   */
  TRUST_VALIDATION_REVIEW(AiAdvisoryTaskType.TRUST_SIGNAL_EXPLANATION);

  private final AiAdvisoryTaskType defaultTaskType;

  RuntimeAssistContextType(AiAdvisoryTaskType defaultTaskType) {
    this.defaultTaskType = defaultTaskType;
  }

  /** Deterministic default task type used when the caller does not pin one explicitly. */
  public AiAdvisoryTaskType defaultTaskType() {
    return defaultTaskType;
  }
}
