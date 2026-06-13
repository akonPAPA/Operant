package com.orderpilot.application.services.runtime;

import com.orderpilot.domain.usage.UsageMath;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OP-CAP-16C Quota + Rate Limit Enforcement — in-process fixed-window rate-limit store.
 *
 * <p>Single-node, deterministic, and dependency-free (no Redis). Each {@code key} (tenant +
 * operation) holds the current window start and its accumulated weight; when the window start
 * advances the counter resets. Accumulation is overflow-safe via {@link UsageMath#safeAdd}.
 *
 * <p>Registered as the default {@link RateLimitStore} bean via {@code CoreConfiguration} using
 * {@code @ConditionalOnMissingBean}, so a future distributed implementation can replace it cleanly.
 */
public class InMemoryRateLimitStore implements RateLimitStore {

  private record Window(long windowStart, long weight) {}

  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

  @Override
  public long addAndGet(String key, long windowStartEpochSeconds, long weight) {
    long safeWeight = UsageMath.clampNonNegative(weight);
    Window updated =
        windows.compute(
            key,
            (k, current) -> {
              if (current == null || current.windowStart() != windowStartEpochSeconds) {
                return new Window(windowStartEpochSeconds, safeWeight);
              }
              return new Window(
                  windowStartEpochSeconds, UsageMath.safeAdd(current.weight(), safeWeight));
            });
    return updated.weight();
  }
}
