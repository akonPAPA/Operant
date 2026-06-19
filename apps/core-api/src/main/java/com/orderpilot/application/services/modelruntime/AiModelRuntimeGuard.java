package com.orderpilot.application.services.modelruntime;

import java.util.EnumSet;
import java.util.Set;

/** Advisory-only guard for model runtime configuration and output categories. */
public final class AiModelRuntimeGuard {
  private static final Set<AiModelAdvisoryAction> ALLOWED_ADVISORY_ACTIONS = EnumSet.of(
      AiModelAdvisoryAction.FINDINGS,
      AiModelAdvisoryAction.RISK_CLASSIFICATION,
      AiModelAdvisoryAction.SUGGESTED_TESTS,
      AiModelAdvisoryAction.CONFIDENCE,
      AiModelAdvisoryAction.CAVEATS,
      AiModelAdvisoryAction.NEXT_REVIEW_RECOMMENDATION);

  private AiModelRuntimeGuard() {}

  public static boolean isAdvisoryOnly(AiModelAdvisoryAction action) {
    return ALLOWED_ADVISORY_ACTIONS.contains(action);
  }

  public static void rejectUnsafeAction(AiModelAdvisoryAction action) {
    if (!isAdvisoryOnly(action)) {
      throw new IllegalArgumentException("model output has no business write authority: " + action);
    }
  }

  public static void validate(AiModelRuntimePolicy policy) {
    if (policy == null) throw new IllegalArgumentException("model runtime policy is required");
    if (!policy.sequentialExecution()) {
      throw new IllegalArgumentException("model runtime must be sequential by default");
    }
    if (policy.enabled() && policy.heavyModel()) {
      throw new IllegalArgumentException("heavy model is opt-in and cannot be enabled by default");
    }
  }
}
