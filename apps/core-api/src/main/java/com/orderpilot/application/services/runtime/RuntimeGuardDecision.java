package com.orderpilot.application.services.runtime;

import com.orderpilot.domain.usage.UsageMetricType;

/**
 * OP-CAP-16C Quota + Rate Limit Enforcement — the combined outcome of a runtime guard check.
 *
 * <p>{@code httpStatusHint} is the HTTP status a caller should map a denial to (200 allowed, 403
 * quota, 429 rate). {@code retryAfterSeconds} is populated (&gt; 0) only for rate-limit denials.
 * This is a decision object: producing it has no side effect on usage counters.
 *
 * @param allowed whether the operation may proceed
 * @param httpStatusHint advisory HTTP status (200 / 403 / 429)
 * @param reasonCode stable reason token (see {@link RuntimeGuardReasonCodes})
 * @param operationType the guarded operation
 * @param metricType nullable quota metric evaluated (null when the operation has no quota dimension)
 * @param requestedUnits clamped requested units
 * @param limit nullable quota limit (null when no policy / no metric)
 * @param used current quota usage in the period
 * @param remaining nullable quota headroom (null when no policy / no metric)
 * @param retryAfterSeconds seconds to wait before retrying (rate-limit denials only; else 0)
 * @param rateLimitBucket nullable safe bucket identifier for observability
 */
public record RuntimeGuardDecision(
    boolean allowed,
    int httpStatusHint,
    String reasonCode,
    RuntimeOperationType operationType,
    UsageMetricType metricType,
    long requestedUnits,
    Long limit,
    long used,
    Long remaining,
    long retryAfterSeconds,
    String rateLimitBucket) {}
