package com.orderpilot.application.services.runtime;

/**
 * OP-CAP-16C Quota + Rate Limit Enforcement — abstraction over the fixed-window counter backing the
 * rate limiter.
 *
 * <p>Stage 16C ships an in-process {@link InMemoryRateLimitStore}. A distributed {@code
 * RedisRateLimitStore} can implement this interface in a later stage without changing the service —
 * Redis is intentionally <b>not</b> introduced now (no Redis is wired in the backend or test
 * profile; see the stage doc).
 */
public interface RateLimitStore {

  /**
   * Add {@code weight} to the counter for {@code key} within the window identified by {@code
   * windowStartEpochSeconds}, resetting to a fresh window when the start advances, and return the new
   * accumulated weight. Implementations must be overflow-safe (saturating).
   */
  long addAndGet(String key, long windowStartEpochSeconds, long weight);
}
