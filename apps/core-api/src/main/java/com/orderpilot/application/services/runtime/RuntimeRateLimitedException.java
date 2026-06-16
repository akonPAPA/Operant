package com.orderpilot.application.services.runtime;

/**
 * OP-CAP-16C Quota + Rate Limit Enforcement — thrown when a runtime operation is denied because the
 * tenant+operation rate-limit window budget is exhausted. Maps to HTTP 429 with stable code {@code
 * RUNTIME_RATE_LIMITED} and a Retry-After hint.
 */
public class RuntimeRateLimitedException extends RuntimeLimitException {
  public RuntimeRateLimitedException(RuntimeGuardDecision decision) {
    super(
        "Runtime rate limit exceeded",
        RuntimeErrorCodes.RUNTIME_RATE_LIMITED,
        429,
        decision);
  }
}
