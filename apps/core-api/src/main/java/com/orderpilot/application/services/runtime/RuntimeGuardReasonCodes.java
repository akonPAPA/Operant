package com.orderpilot.application.services.runtime;

/**
 * OP-CAP-16C Quota + Rate Limit Enforcement — stable reason tokens for runtime guard decisions. Safe
 * for metrics/audit; never contains raw input text.
 */
public final class RuntimeGuardReasonCodes {
  private RuntimeGuardReasonCodes() {}

  /** Request allowed: no constraints triggered. */
  public static final String ALLOWED = "ALLOWED";

  /** The required feature is available to the tenant. */
  public static final String FEATURE_AVAILABLE = "FEATURE_AVAILABLE";

  /** The required feature is not available to the tenant (explicitly disabled entitlement). */
  public static final String FEATURE_NOT_AVAILABLE = "FEATURE_NOT_AVAILABLE";

  /** OP-CAP-16E: the tenant has a plan but none is currently active (suspended/expired/disabled). */
  public static final String FEATURE_PLAN_NOT_ACTIVE = "FEATURE_PLAN_NOT_ACTIVE";

  /** OP-CAP-16E: active plan is authoritative but grants no effective entitlement for the feature. */
  public static final String FEATURE_NOT_ENTITLED = "FEATURE_NOT_ENTITLED";

  /** OP-CAP-16E: an entitlement existed but its effective window has ended. */
  public static final String FEATURE_ENTITLEMENT_EXPIRED = "FEATURE_ENTITLEMENT_EXPIRED";

  /** OP-CAP-16E: tenant has no persisted plan; allowed by the safe backward-compatibility default. */
  public static final String FEATURE_POLICY_COMPAT_DEFAULT = "FEATURE_POLICY_COMPAT_DEFAULT";

  /**
   * Whether {@code reasonCode} represents a feature-entitlement denial (maps to {@code
   * RuntimeFeatureNotAvailableException} → 403). Allow reasons ({@link #FEATURE_AVAILABLE}, {@link
   * #FEATURE_POLICY_COMPAT_DEFAULT}) are not denials.
   */
  public static boolean isFeatureDenial(String reasonCode) {
    return FEATURE_NOT_AVAILABLE.equals(reasonCode)
        || FEATURE_PLAN_NOT_ACTIVE.equals(reasonCode)
        || FEATURE_NOT_ENTITLED.equals(reasonCode)
        || FEATURE_ENTITLEMENT_EXPIRED.equals(reasonCode);
  }

  /** No quota policy configured for the tenant/metric; allowed by default. */
  public static final String NO_POLICY = "NO_POLICY";

  /** A quota policy exists and used + requested stays within the limit. */
  public static final String WITHIN_LIMIT = "WITHIN_LIMIT";

  /** A quota policy exists and used + requested exceeds the limit. */
  public static final String QUOTA_LIMIT_EXCEEDED = "QUOTA_LIMIT_EXCEEDED";

  /** Rate-limit weighted budget for the current window has room. */
  public static final String RATE_LIMIT_WITHIN_WINDOW = "RATE_LIMIT_WITHIN_WINDOW";

  /** Rate-limit weighted budget for the current window is exhausted. */
  public static final String RATE_LIMIT_EXCEEDED = "RATE_LIMIT_EXCEEDED";
}
