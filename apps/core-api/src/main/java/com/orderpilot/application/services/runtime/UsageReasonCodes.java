package com.orderpilot.application.services.runtime;

/**
 * OP-CAP-16B Usage Metering Foundation — stable reason tokens for quota decisions. These are safe
 * for metrics/audit and never contain raw input text.
 */
public final class UsageReasonCodes {
  private UsageReasonCodes() {}

  /** No quota policy is configured for the tenant/metric; allowed by default. */
  public static final String NO_POLICY = "NO_POLICY";

  /** A policy exists and used + additional stays within the limit. */
  public static final String WITHIN_LIMIT = "WITHIN_LIMIT";

  /** A policy exists and used + additional exceeds the limit (advisory denial only in 16B). */
  public static final String LIMIT_EXCEEDED = "LIMIT_EXCEEDED";
}
