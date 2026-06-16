package com.orderpilot.application.services.runtime;

/**
 * OP-CAP-16C Quota + Rate Limit Enforcement — base for runtime guard denials that map to a stable
 * API error.
 *
 * <p>Carries the stable error code, HTTP status, optional Retry-After, the stable reason code, and
 * the originating decision. {@code GlobalExceptionHandler} maps any subclass to the existing {@code
 * ApiErrorResponse} shape. Messages contain only safe tokens — never raw input text.
 */
public abstract class RuntimeLimitException extends RuntimeException {
  private final String errorCode;
  private final int httpStatus;
  private final long retryAfterSeconds;
  private final transient RuntimeGuardDecision decision;

  protected RuntimeLimitException(
      String message, String errorCode, int httpStatus, RuntimeGuardDecision decision) {
    super(message);
    this.errorCode = errorCode;
    this.httpStatus = httpStatus;
    this.retryAfterSeconds = decision == null ? 0L : decision.retryAfterSeconds();
    this.decision = decision;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public int getHttpStatus() {
    return httpStatus;
  }

  public long getRetryAfterSeconds() {
    return retryAfterSeconds;
  }

  public RuntimeGuardDecision getDecision() {
    return decision;
  }
}
