package com.orderpilot.application.services.runtime;

/**
 * OP-CAP-16C Quota + Rate Limit Enforcement — stable API error codes for denied runtime guard
 * decisions. These map to the existing {@code ApiErrorResponse} shape via {@code
 * GlobalExceptionHandler}.
 */
public final class RuntimeErrorCodes {
  private RuntimeErrorCodes() {}

  /** Quota/entitlement denial → HTTP 403. */
  public static final String RUNTIME_QUOTA_EXCEEDED = "RUNTIME_QUOTA_EXCEEDED";

  /** Rate-limit denial → HTTP 429 (with Retry-After). */
  public static final String RUNTIME_RATE_LIMITED = "RUNTIME_RATE_LIMITED";

  /**
   * Feature/entitlement not available for the tenant's plan → HTTP 403. Reserved for a later stage:
   * Stage 16C does not implement a plan/entitlement model (see stage doc), so nothing throws this
   * yet. The constant exists so the code namespace is stable.
   */
  public static final String RUNTIME_FEATURE_NOT_AVAILABLE = "RUNTIME_FEATURE_NOT_AVAILABLE";
}
