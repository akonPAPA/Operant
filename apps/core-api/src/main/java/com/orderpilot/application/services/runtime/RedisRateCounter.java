package com.orderpilot.application.services.runtime;

/**
 * OP-CAP-16K — the minimal atomic counter operation {@link RedisRateLimitStore} needs from Redis.
 *
 * <p>Isolating this one operation keeps the Redis client (Lettuce/{@code StringRedisTemplate}) out of
 * the store's logic, so the store's key construction and fail-open/closed behavior are unit-testable
 * without a running Redis. The production implementation
 * ({@code LettuceRedisRateCounter}) performs an atomic INCRBY + first-write EXPIRE via a Lua script.
 */
public interface RedisRateCounter {

  /**
   * Atomically add {@code delta} to {@code key} and return the new value, setting the key's TTL to
   * {@code ttlSeconds} on its first write within the window. Implementations must be atomic so a crash
   * cannot leave the counter without an expiry.
   *
   * @throws RuntimeException if the backing store is unreachable (the caller decides fail-open/closed)
   */
  long incrementAndGet(String key, long delta, long ttlSeconds);
}
