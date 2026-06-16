package com.orderpilot.application.services.runtime;

/**
 * OP-CAP-16D Runtime Guard Live Wiring — thrown when a runtime operation is denied because the
 * tenant's plan does not include the required feature. Maps (via the existing {@code
 * GlobalExceptionHandler} {@link RuntimeLimitException} handler) to HTTP 403 with stable code {@code
 * RUNTIME_FEATURE_NOT_AVAILABLE}.
 *
 * <p>The message contains no tenant plan internals.
 */
public class RuntimeFeatureNotAvailableException extends RuntimeLimitException {
  public RuntimeFeatureNotAvailableException(RuntimeGuardDecision decision) {
    super(
        "Runtime feature not available",
        RuntimeErrorCodes.RUNTIME_FEATURE_NOT_AVAILABLE,
        403,
        decision);
  }
}
