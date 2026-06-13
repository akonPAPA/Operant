package com.orderpilot.application.services.runtime;

import com.orderpilot.domain.usage.UsageMetricType;

/**
 * OP-CAP-16B Usage Metering Foundation — advisory outcome of {@code checkQuota}.
 *
 * <p>This is a decision object only. Stage 16B never blocks a live product request based on it;
 * {@code allowed=false} simply reports that usage would exceed a configured limit. Live enforcement
 * is deferred to Stage 16C.
 *
 * @param allowed whether the additional units stay within the limit (always true when no policy)
 * @param metricType the metric checked
 * @param periodKey deterministic period key the check evaluated against
 * @param limitUnits nullable configured limit ({@code null} when no policy exists)
 * @param usedUnits current counter total
 * @param requestedAdditionalUnits the additional units being evaluated
 * @param remainingUnits nullable headroom under the limit ({@code null} when no policy)
 * @param reasonCode stable reason token (see {@link UsageReasonCodes})
 */
public record QuotaDecision(
    boolean allowed,
    UsageMetricType metricType,
    String periodKey,
    Long limitUnits,
    long usedUnits,
    long requestedAdditionalUnits,
    Long remainingUnits,
    String reasonCode) {}
