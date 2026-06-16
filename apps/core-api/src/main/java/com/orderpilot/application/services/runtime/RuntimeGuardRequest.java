package com.orderpilot.application.services.runtime;

import com.orderpilot.domain.usage.UsageMetricType;
import java.util.UUID;

/**
 * OP-CAP-16C Quota + Rate Limit Enforcement — a request to check whether an expensive runtime
 * operation may proceed for a tenant.
 *
 * <p>All fields are typed, bounded, safe tokens — there is no free-form text metadata. {@code
 * metricType} is optional: when null the guard derives a default metric from {@code operationType}
 * (and some read-only operations have no quota dimension at all).
 *
 * @param tenantId required tenant scope
 * @param operationType required operation being guarded
 * @param metricType optional explicit quota metric; null → derived from {@code operationType}
 * @param requestedUnits units the operation would consume; clamped non-negative
 * @param idempotencyKey optional (carried for callers; the guard itself records no usage)
 * @param actorType optional safe actor token (e.g. {@code OPERATOR}, {@code BOT}, {@code SYSTEM})
 * @param source optional safe source token
 */
public record RuntimeGuardRequest(
    UUID tenantId,
    RuntimeOperationType operationType,
    UsageMetricType metricType,
    long requestedUnits,
    String idempotencyKey,
    String actorType,
    String source) {

  /** Convenience for the common case: tenant + operation + requested units only. */
  public static RuntimeGuardRequest of(
      UUID tenantId, RuntimeOperationType operationType, long requestedUnits) {
    return new RuntimeGuardRequest(
        tenantId, operationType, null, requestedUnits, null, null, null);
  }
}
