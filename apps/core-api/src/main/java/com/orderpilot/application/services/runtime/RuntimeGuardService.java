package com.orderpilot.application.services.runtime;

import com.orderpilot.domain.usage.UsageMath;
import org.springframework.stereotype.Service;

/**
 * OP-CAP-16C/16D runtime guard — orchestration entry point expensive runtime paths call <b>before</b>
 * creating a job or doing any AI/model/heavy work.
 *
 * <p>Order matters (16D): feature entitlement → quota → rate. The feature check (cheapest, no DB
 * write) runs first; if the tenant lacks the feature the call returns/throws immediately and neither
 * the quota read nor the rate budget is touched. Next the read-only quota check; if it denies, the
 * rate-limit window budget is <b>not</b> consumed. Only when feature and quota pass does the weighted
 * rate-limit check run and consume budget.
 *
 * <p>Everything is tenant-scoped and deterministic. This service never records usage, never calls AI
 * or external services, and never mutates business tables — it only produces a {@link
 * RuntimeGuardDecision} (or, via {@link #enforce}, throws a stable mapped exception).
 *
 * <p>The original 16C API (no feature argument) is preserved and simply skips the entitlement gate.
 */
@Service
public class RuntimeGuardService {
  private final QuotaGuard quotaGuard;
  private final RateLimitService rateLimitService;
  private final FeatureEntitlementGuard featureEntitlementGuard;

  public RuntimeGuardService(
      QuotaGuard quotaGuard,
      RateLimitService rateLimitService,
      FeatureEntitlementGuard featureEntitlementGuard) {
    this.quotaGuard = quotaGuard;
    this.rateLimitService = rateLimitService;
    this.featureEntitlementGuard = featureEntitlementGuard;
  }

  /**
   * Feature-aware combined check: entitlement → quota → rate. A null {@code featureType} skips the
   * entitlement gate (preserving 16C behavior). Returns a decision; never throws on a denial.
   */
  public RuntimeGuardDecision checkRuntimeGuard(
      RuntimeGuardRequest request, RuntimeFeatureType featureType) {
    if (request == null) throw new IllegalArgumentException("runtime guard request is required");

    if (featureType != null) {
      FeatureEntitlementDecision feature =
          featureEntitlementGuard.checkFeature(request.tenantId(), featureType);
      if (!feature.available()) {
        // Entitlement denial short-circuits before any quota read or rate budget consumption.
        // The policy's stable reason code (16E: plan-not-active / not-entitled / expired / disabled)
        // is surfaced; the HTTP hint stays 403.
        return new RuntimeGuardDecision(
            false,
            feature.httpStatusHint(),
            feature.reasonCode(),
            request.operationType(),
            null,
            UsageMath.clampNonNegative(request.requestedUnits()),
            null,
            0L,
            null,
            0L,
            null);
      }
    }
    return checkRuntimeGuard(request);
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
   * Enforcing variant (no feature gate — 16C-compatible): returns the decision when allowed,
   * otherwise throws the stable mapped exception ({@link RuntimeQuotaExceededException} → 403,
   * {@link RuntimeRateLimitedException} → 429).
   */
  public RuntimeGuardDecision enforce(RuntimeGuardRequest request) {
    return enforce(request, null);
  }

  /**
   * Feature-aware enforcing variant: returns the decision when allowed, otherwise throws the stable
   * mapped exception ({@link RuntimeFeatureNotAvailableException} → 403, {@link
   * RuntimeQuotaExceededException} → 403, {@link RuntimeRateLimitedException} → 429). Expensive paths
   * call this and let it short-circuit before any heavy work.
   */
  public RuntimeGuardDecision enforce(RuntimeGuardRequest request, RuntimeFeatureType featureType) {
    RuntimeGuardDecision decision = checkRuntimeGuard(request, featureType);
    if (decision.allowed()) {
      return decision;
    }
    if (RuntimeGuardReasonCodes.isFeatureDenial(decision.reasonCode())) {
      throw new RuntimeFeatureNotAvailableException(decision);
    }
    if (RuntimeGuardReasonCodes.RATE_LIMIT_EXCEEDED.equals(decision.reasonCode())) {
      throw new RuntimeRateLimitedException(decision);
    }
    throw new RuntimeQuotaExceededException(decision);
  }

  /**
   * OP-CAP-16F enforcing variant for operator-initiated / bulk operations: entitlement → quota only,
   * with <b>no</b> rate-limit consumption. Rate limiting is reserved for high-frequency automated hot
   * paths (e.g. AI extraction); a deliberate operator action like activating a bulk import or running
   * a reconciliation projection may legitimately burst, so it must not be throttled by the per-minute
   * window — while still being gated by feature entitlement and quota. Throws {@link
   * RuntimeFeatureNotAvailableException} (403) or {@link RuntimeQuotaExceededException} (403) on denial;
   * returns the allowed decision otherwise. Ordering (entitlement before quota) is preserved.
   */
  public RuntimeGuardDecision enforceWithoutRate(
      RuntimeGuardRequest request, RuntimeFeatureType featureType) {
    if (request == null) throw new IllegalArgumentException("runtime guard request is required");

    if (featureType != null) {
      FeatureEntitlementDecision feature =
          featureEntitlementGuard.checkFeature(request.tenantId(), featureType);
      if (!feature.available()) {
        throw new RuntimeFeatureNotAvailableException(
            new RuntimeGuardDecision(
                false,
                feature.httpStatusHint(),
                feature.reasonCode(),
                request.operationType(),
                null,
                UsageMath.clampNonNegative(request.requestedUnits()),
                null,
                0L,
                null,
                0L,
                null));
      }
    }

    RuntimeGuardDecision quota = quotaGuard.checkQuota(request);
    if (!quota.allowed()) {
      throw new RuntimeQuotaExceededException(quota);
    }
    return quota;
  }

  /** Direct feature entitlement check (read-only), exposed for callers that only gate a feature. */
  public FeatureEntitlementDecision checkFeature(
      RuntimeGuardRequest request, RuntimeFeatureType featureType) {
    if (request == null) throw new IllegalArgumentException("runtime guard request is required");
    return featureEntitlementGuard.checkFeature(request.tenantId(), featureType);
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
