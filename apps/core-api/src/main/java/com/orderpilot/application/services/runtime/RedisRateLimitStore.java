package com.orderpilot.application.services.runtime;

import com.orderpilot.domain.usage.UsageMath;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OP-CAP-16K — distributed, multi-node-safe fixed-window rate-limit store backed by Redis counters.
 *
 * <p>Redis here is <b>only</b> a distributed counter for runtime rate limiting. It never stores tenant
 * plans, feature entitlements, quota records, audit, business state, or AI output. The runtime guard
 * ordering (entitlement → quota → rate) is unchanged — this store is consulted only after entitlement
 * and quota allow, and {@code enforceWithoutRate(...)} paths never reach it.
 *
 * <p>Keys are {@code {prefix}:{tenantId}:{operation}:{windowStart}} built only from already-safe
 * identifiers (a UUID and an enum name); a defensive whitelist guards against any unexpected
 * characters. The counter is incremented atomically with a first-write TTL of {@code windowSeconds +
 * buffer} (see {@link RedisRateCounter}).
 *
 * <p>Failure mode is explicit: if Redis is unreachable, a <b>fail-closed</b> store (default) returns a
 * saturating value so the guard denies (a stable 429), while a fail-open store returns just this
 * request's weight so the request is allowed. There is no silent fallback to the in-memory store.
 */
public class RedisRateLimitStore implements RateLimitStore {
  private static final Logger log = LoggerFactory.getLogger(RedisRateLimitStore.class);
  private static final Pattern SAFE_KEY_PART = Pattern.compile("[^A-Za-z0-9:_-]");

  private final RedisRateCounter counter;
  private final String keyPrefix;
  private final long ttlBufferSeconds;
  private final boolean failOpen;

  public RedisRateLimitStore(RedisRateCounter counter, String keyPrefix, long ttlBufferSeconds, boolean failOpen) {
    this.counter = counter;
    this.keyPrefix = normalize(keyPrefix == null || keyPrefix.isBlank() ? "orderpilot:runtime-rate" : keyPrefix);
    this.ttlBufferSeconds = Math.max(0L, ttlBufferSeconds);
    this.failOpen = failOpen;
  }

  @Override
  public long addAndGet(String key, long windowStartEpochSeconds, long windowSeconds, long weight) {
    long safeWeight = UsageMath.clampNonNegative(weight);
    long ttl = Math.max(1L, windowSeconds) + ttlBufferSeconds;
    String redisKey = keyPrefix + ":" + normalize(key) + ":" + windowStartEpochSeconds;
    try {
      return counter.incrementAndGet(redisKey, safeWeight, ttl);
    } catch (RuntimeException ex) {
      if (failOpen) {
        // Fail-open: behave as if this is the only request in the window (allow). Logged without
        // exposing key/secret details to API users.
        log.warn("Redis rate store unavailable; failing OPEN for this request");
        return safeWeight;
      }
      // Fail-closed (default): saturate so the guard denies with a stable rate-limit error.
      log.warn("Redis rate store unavailable; failing CLOSED for this request");
      return Long.MAX_VALUE;
    }
  }

  private static String normalize(String value) {
    if (value == null) {
      return "";
    }
    return SAFE_KEY_PART.matcher(value).replaceAll("_");
  }
}
