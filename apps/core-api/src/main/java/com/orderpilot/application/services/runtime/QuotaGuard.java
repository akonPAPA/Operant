package com.orderpilot.application.services.runtime;

import com.orderpilot.domain.usage.UsageMath;
import com.orderpilot.domain.usage.UsageMetricType;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * OP-CAP-16C Quota + Rate Limit Enforcement — read-only quota guard.
 *
 * <p>Reuses the OP-CAP-16B {@code UsageMeterService.checkQuota(...)} advisory check (the {@code
 * QuotaPolicy} table is the enforcement source) and adapts it into a {@link RuntimeGuardDecision}.
 * This guard performs no usage recording and no counter mutation — usage metering stays explicit.
 *
 * <p>Behavior:
 *
 * <ul>
 *   <li>Operation with no quota dimension (e.g. {@code SEARCH_QUERY}) → allow, {@code NO_POLICY}.
 *   <li>No policy for the metric → allow, {@code NO_POLICY}.
 *   <li>used + requested ≤ limit → allow, {@code WITHIN_LIMIT}.
 *   <li>used + requested &gt; limit → deny (403), {@code QUOTA_LIMIT_EXCEEDED}.
 * </ul>
 *
 * <p>Requested units follow the 16B convention: negative/absurd values are clamped to a non-negative
 * {@code long}; accumulation is overflow-safe.
 */
@Service
public class QuotaGuard {
  private final UsageMeterService usageMeterService;

  public QuotaGuard(UsageMeterService usageMeterService) {
    this.usageMeterService = usageMeterService;
  }

  public RuntimeGuardDecision checkQuota(RuntimeGuardRequest request) {
    if (request == null) throw new IllegalArgumentException("runtime guard request is required");
    UUID tenantId = request.tenantId();
    if (tenantId == null) throw new IllegalArgumentException("tenantId is required");
    RuntimeOperationType operationType = request.operationType();
    if (operationType == null) throw new IllegalArgumentException("operationType is required");

    long requested = UsageMath.clampNonNegative(request.requestedUnits());
    UsageMetricType metricType = effectiveMetric(request);

    // No quota dimension for this operation → allow by default.
    if (metricType == null) {
      return new RuntimeGuardDecision(
          true,
          200,
          RuntimeGuardReasonCodes.NO_POLICY,
          operationType,
          null,
          requested,
          null,
          0L,
          null,
          0L,
          null);
    }

    QuotaDecision quota = usageMeterService.checkQuota(tenantId, metricType, requested);
    boolean allowed = quota.allowed();
    String reasonCode =
        allowed
            ? quota.reasonCode() // NO_POLICY or WITHIN_LIMIT (already stable tokens)
            : RuntimeGuardReasonCodes.QUOTA_LIMIT_EXCEEDED;
    int httpHint = allowed ? 200 : 403;

    return new RuntimeGuardDecision(
        allowed,
        httpHint,
        reasonCode,
        operationType,
        metricType,
        requested,
        quota.limitUnits(),
        quota.usedUnits(),
        quota.remainingUnits(),
        0L,
        null);
  }

  private static UsageMetricType effectiveMetric(RuntimeGuardRequest request) {
    if (request.metricType() != null) {
      return request.metricType();
    }
    return EndpointWeightPolicy.defaultMetricFor(request.operationType());
  }
}
