package com.orderpilot.application.services.runtime;

import java.util.UUID;

/**
 * OP-CAP-27 Runtime Control Mainline — the trusted, server-resolved input to a runtime-control
 * decision. Every field is a typed, bounded token resolved by the backend; there is no free-form
 * authority a client could spoof (tenant/actor/source/workload type are resolved by the caller from
 * {@code TenantContext}/{@code RequestActorResolver}/trusted lookups, never from a request body).
 *
 * @param tenantId required tenant scope
 * @param actorId resolved actor; {@code null} marks a trusted system/job actor
 * @param operationType the guarded runtime operation (drives quota metric + rate rule)
 * @param featureType optional entitlement feature to gate first; {@code null} skips the entitlement gate
 * @param classification deterministic workload classification input (never echoed/logged)
 * @param requestedUnits units the operation would consume; a negative value falls back to the
 *     classifier's estimated input units
 * @param idempotencyKey optional dedup key (the control service records no usage and writes nothing)
 * @param duplicateDetected whether the caller has already found an existing result for the key
 */
public record RuntimeControlRequest(
    UUID tenantId,
    UUID actorId,
    RuntimeOperationType operationType,
    RuntimeFeatureType featureType,
    AiWorkloadClassificationRequest classification,
    long requestedUnits,
    String idempotencyKey,
    boolean duplicateDetected) {

  /** Convenience: tenant + actor + operation + feature + classification, units derived from the classifier. */
  public static RuntimeControlRequest of(
      UUID tenantId,
      UUID actorId,
      RuntimeOperationType operationType,
      RuntimeFeatureType featureType,
      AiWorkloadClassificationRequest classification) {
    return new RuntimeControlRequest(
        tenantId, actorId, operationType, featureType, classification, -1L, null, false);
  }
}
