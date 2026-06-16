package com.orderpilot.application.services.runtime;

/**
 * OP-CAP-16C Quota + Rate Limit Enforcement — a fixed-window rate-limit rule.
 *
 * @param windowSeconds window length in seconds (must be &gt; 0)
 * @param maxWeight maximum accumulated cost weight permitted per window per tenant+operation
 */
public record RateLimitRule(long windowSeconds, long maxWeight) {
  public RateLimitRule {
    if (windowSeconds <= 0) {
      throw new IllegalArgumentException("windowSeconds must be positive");
    }
    if (maxWeight < 0) {
      throw new IllegalArgumentException("maxWeight must be non-negative");
    }
  }
}
