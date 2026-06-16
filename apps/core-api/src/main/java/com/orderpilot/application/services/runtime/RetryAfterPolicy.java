package com.orderpilot.application.services.runtime;

/**
 * OP-CAP-16C Quota + Rate Limit Enforcement — deterministic Retry-After calculation for 429
 * decisions. Given the current epoch second and the window boundary, returns the whole seconds until
 * the window ends, clamped to at least 1 so a denied caller is always told to wait a positive amount.
 */
public final class RetryAfterPolicy {
  private RetryAfterPolicy() {}

  /**
   * Seconds until the current fixed window ends.
   *
   * @param nowEpochSeconds current time (epoch seconds, UTC)
   * @param windowStartEpochSeconds start of the current window (epoch seconds)
   * @param windowSeconds window length
   * @return seconds to wait, always &gt;= 1
   */
  public static long retryAfterSeconds(
      long nowEpochSeconds, long windowStartEpochSeconds, long windowSeconds) {
    long windowEnd = windowStartEpochSeconds + windowSeconds;
    long remaining = windowEnd - nowEpochSeconds;
    return Math.max(1L, remaining);
  }
}
