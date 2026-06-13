package com.orderpilot.application.services.runtime;

/**
 * OP-CAP-16C Quota + Rate Limit Enforcement — abstraction over the fixed-window counter backing the
 * rate limiter.
 *
 * <p>Stage 16C ships an in-process {@link InMemoryRateLimitStore}. OP-CAP-16K adds a distributed
 * {@code RedisRateLimitStore} implementing this same interface, selected by
 * {@code orderpilot.runtime.rate.store=redis}; in-memory remains the default for single-node/dev/test.
 */
public interface RateLimitStore {

  /**
   * Add {@code weight} to the counter for {@code key} within the window identified by {@code
   * windowStartEpochSeconds}, resetting to a fresh window when the start advances, and return the new
   * accumulated weight. Implementations must be overflow-safe (saturating).
   *
   * @param windowSeconds the window length, used by distributed implementations to set a counter TTL
   *     (the in-memory implementation ignores it). Lets the counter expire shortly after the window.
   */
  long addAndGet(String key, long windowStartEpochSeconds, long windowSeconds, long weight);
}
