package com.orderpilot.application.services.runtime;

import org.springframework.stereotype.Service;

/**
 * OP-CAP-16C Quota + Rate Limit Enforcement — orchestration entry point expensive runtime paths call
 * <b>before</b> creating a job or doing any AI/model/heavy work.
 *
 * <p>Order matters: the cheap, read-only quota check runs first. If quota denies, the call returns
 * immediately and the rate-limit window budget is <b>not</b> consumed — a denied request never burns
 * the rate budget or triggers downstream work. Only when quota allows does the weighted rate-limit
 * check run.
 *
 * <p>Everything is tenant-scoped and deterministic. This service never records usage, never calls AI
 * or external services, and never mutates business tables — it only produces a {@link
 * RuntimeGuardDecision} (or, via {@link #enforce}, throws a stable mapped exception).
 */
@Service
public class RuntimeGuardService {
  private final QuotaGuard quotaGuard;
  private final RateLimitService rateLimitService;

  public RuntimeGuardService(QuotaGuard quotaGuard, RateLimitService rateLimitService) {
    this.quotaGuard = quotaGuard;
    this.rateLimitService = rateLimitService;
  }

  /** Combined quota-then-rate check. Returns a decision; never throws on a denial. */
  public RuntimeGuardDecision checkRuntimeGuard(RuntimeGuardRequest request) {
    if (request == null) throw new IllegalArgumentException("runtime guard request is required");

    RuntimeGuardDecision quota = quotaGuard.checkQuota(request);
    if (!quota.allowed()) {
      // Deny before consuming any rate budget or doing work.
      return quota;
    }

    RateLimitDecision rate =
        rateLimitService.checkRateLimit(request.tenantId(), request.operationType());
    if (!rate.allowed()) {
      return new RuntimeGuardDecision(
          false,
          429,
          RuntimeGuardReasonCodes.RATE_LIMIT_EXCEEDED,
          request.operationType(),
          quota.metricType(),
          quota.requestedUnits(),
          quota.limit(),
          quota.used(),
          quota.remaining(),
          rate.retryAfterSeconds(),
          rate.bucket());
    }

    // Allowed by both quota and rate limit.
    return new RuntimeGuardDecision(
        true,
        200,
        RuntimeGuardReasonCodes.ALLOWED,
        request.operationType(),
        quota.metricType(),
        quota.requestedUnits(),
        quota.limit(),
        quota.used(),
        quota.remaining(),
        0L,
        rate.bucket());
  }

  /**
   * Enforcing variant: returns the decision when allowed, otherwise throws the stable mapped
   * exception ({@link RuntimeQuotaExceededException} → 403, {@link RuntimeRateLimitedException} →
   * 429). Expensive paths call this and let it short-circuit before any heavy work.
   */
  public RuntimeGuardDecision enforce(RuntimeGuardRequest request) {
    RuntimeGuardDecision decision = checkRuntimeGuard(request);
    if (decision.allowed()) {
      return decision;
    }
    if (RuntimeGuardReasonCodes.RATE_LIMIT_EXCEEDED.equals(decision.reasonCode())) {
      throw new RuntimeRateLimitedException(decision);
    }
    throw new RuntimeQuotaExceededException(decision);
  }

  /** Direct rate-limit check (consumes window budget), exposed for callers that only rate-limit. */
  public RateLimitDecision checkRateLimit(RuntimeGuardRequest request) {
    if (request == null) throw new IllegalArgumentException("runtime guard request is required");
    return rateLimitService.checkRateLimit(request.tenantId(), request.operationType());
  }

  /** Direct quota check (read-only), exposed for callers that only need quota. */
  public RuntimeGuardDecision checkQuota(RuntimeGuardRequest request) {
    return quotaGuard.checkQuota(request);
  }
}
