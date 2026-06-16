package com.orderpilot.application.services.runtime;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * OP-CAP-16C Quota + Rate Limit Enforcement — tenant-scoped, operation-scoped, weighted fixed-window
 * rate limiter.
 *
 * <p>Deterministic: the window boundary is derived from an injected {@link Clock}, and the cost
 * weight and budget come from the code-defined {@link EndpointWeightPolicy}. No external Redis is
 * required; the backing {@link RateLimitStore} defaults to {@link InMemoryRateLimitStore}.
 *
 * <p>Each check consumes the operation's weight from the current window (a rate limiter counts the
 * attempt). It never touches the 16B usage counters — usage metering remains explicit through {@code
 * UsageMeterService.recordUsage(...)}.
 */
@Service
public class RateLimitService {
  private final RateLimitStore store;
  private final Clock clock;

  public RateLimitService(RateLimitStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  /** Check (and consume) the weighted window budget for {@code tenantId} + {@code operationType}. */
  public RateLimitDecision checkRateLimit(UUID tenantId, RuntimeOperationType operationType) {
    if (tenantId == null) throw new IllegalArgumentException("tenantId is required");
    if (operationType == null) throw new IllegalArgumentException("operationType is required");

    RateLimitRule rule = EndpointWeightPolicy.ruleFor(operationType);
    long weight = EndpointWeightPolicy.weightFor(operationType);

    Instant now = clock.instant();
    long nowEpoch = now.getEpochSecond();
    long windowStart = Math.floorDiv(nowEpoch, rule.windowSeconds()) * rule.windowSeconds();
    String key = tenantId + ":" + operationType.name();
    String bucket = key + "@" + windowStart;

    long windowUsed = store.addAndGet(key, windowStart, rule.windowSeconds(), weight);
    boolean allowed = windowUsed <= rule.maxWeight();
    long retryAfter =
        allowed ? 0L : RetryAfterPolicy.retryAfterSeconds(nowEpoch, windowStart, rule.windowSeconds());
    String reason =
        allowed
            ? RuntimeGuardReasonCodes.RATE_LIMIT_WITHIN_WINDOW
            : RuntimeGuardReasonCodes.RATE_LIMIT_EXCEEDED;

    return new RateLimitDecision(
        allowed, reason, bucket, weight, rule.maxWeight(), windowUsed, retryAfter);
  }
}
