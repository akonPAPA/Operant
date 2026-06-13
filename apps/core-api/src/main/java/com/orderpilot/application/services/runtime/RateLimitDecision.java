package com.orderpilot.application.services.runtime;

/**
 * OP-CAP-16C Quota + Rate Limit Enforcement — outcome of a tenant + operation rate-limit check.
 *
 * @param allowed whether the weighted window budget had room for this operation
 * @param reasonCode stable reason token (see {@link RuntimeGuardReasonCodes})
 * @param bucket safe bucket identifier ({@code tenant:operation@windowStart})
 * @param weightConsumed the cost weight this operation added to the window
 * @param windowBudget the maximum weighted budget for the window
 * @param windowUsed the accumulated weighted usage after this operation
 * @param retryAfterSeconds seconds until the current window ends (denials only; else 0)
 */
public record RateLimitDecision(
    boolean allowed,
    String reasonCode,
    String bucket,
    long weightConsumed,
    long windowBudget,
    long windowUsed,
    long retryAfterSeconds) {}
