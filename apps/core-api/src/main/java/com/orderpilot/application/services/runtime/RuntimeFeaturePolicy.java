package com.orderpilot.application.services.runtime;

import java.util.UUID;

/**
 * OP-CAP-16D Runtime Guard Live Wiring — tenant-scoped feature availability policy.
 *
 * <p>Code-defined for this stage (no billing, no subscription table). The default {@link
 * PermissiveRuntimeFeaturePolicy} preserves the existing product convention — features are available
 * unless a policy explicitly denies them — matching the 16B quota "allow when no policy" and 16C
 * rate "allow within budget" defaults.
 *
 * <p>A later stage can supply a persistent, tenant-entitlement-backed implementation behind this same
 * interface (registered via {@code @ConditionalOnMissingBean}) without changing the guard.
 */
public interface RuntimeFeaturePolicy {

  /** Whether {@code feature} is available to {@code tenantId}. Must be cheap and side-effect free. */
  boolean isAvailable(UUID tenantId, RuntimeFeatureType feature);

  /**
   * OP-CAP-16E — richer evaluation carrying a stable reason code. The default wraps {@link
   * #isAvailable} into a binary {@code FEATURE_AVAILABLE}/{@code FEATURE_NOT_AVAILABLE} decision so
   * existing boolean policies keep working; a persistent policy overrides this to report precise
   * reasons (plan-not-active, not-entitled, expired, compatibility default).
   */
  default FeatureEntitlementDecision evaluate(UUID tenantId, RuntimeFeatureType feature) {
    boolean available = isAvailable(tenantId, feature);
    return new FeatureEntitlementDecision(
        available,
        feature,
        available
            ? RuntimeGuardReasonCodes.FEATURE_AVAILABLE
            : RuntimeGuardReasonCodes.FEATURE_NOT_AVAILABLE,
        available ? 200 : 403);
  }
}
