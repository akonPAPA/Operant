package com.orderpilot.application.services.runtime;

/**
 * OP-CAP-16C Quota + Rate Limit Enforcement — stable reason tokens for runtime guard decisions. Safe
 * for metrics/audit; never contains raw input text.
 */
public final class RuntimeGuardReasonCodes {
  private RuntimeGuardReasonCodes() {}

  /** Request allowed: no constraints triggered. */
  public static final String ALLOWED = "ALLOWED";

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
