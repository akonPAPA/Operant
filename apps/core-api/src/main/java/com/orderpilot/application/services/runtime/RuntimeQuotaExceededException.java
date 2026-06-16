package com.orderpilot.application.services.runtime;

/**
 * OP-CAP-16C Quota + Rate Limit Enforcement — thrown when a runtime operation is denied because it
 * would exceed a tenant quota. Maps to HTTP 403 with stable code {@code RUNTIME_QUOTA_EXCEEDED}.
 */
public class RuntimeQuotaExceededException extends RuntimeLimitException {
  public RuntimeQuotaExceededException(RuntimeGuardDecision decision) {
    super(
        "Runtime quota exceeded",
        RuntimeErrorCodes.RUNTIME_QUOTA_EXCEEDED,
        403,
        decision);
  }
}
