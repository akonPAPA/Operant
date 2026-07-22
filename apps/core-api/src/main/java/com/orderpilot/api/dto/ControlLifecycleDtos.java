package com.orderpilot.api.dto;

import java.time.Instant;

/**
 * P1-E2A - bounded request/response contracts for the durable backup-operation control slice.
 *
 * <p>The request DTOs declare no authority fields. Unknown properties cannot reach the service because
 * there is no declared component for operation state, executor identity, principal identity,
 * path/command/database/container/image/environment, or any permission value. Authority is resolved by
 * the backend from the verified control credential and the exact route.
 *
 * <p>Business-significant request values are carried in the signed JSON body. In particular, the backup
 * idempotency key is body-bound by the control-plane content hash instead of being accepted from an
 * unsigned auxiliary header.
 *
 * <p>Response DTOs expose only bounded, operator/executor-safe fields. They never expose the internal
 * UUID primary key, the idempotency-key hash, principal fingerprints, or any persistence/security
 * internal.
 */
public final class ControlLifecycleDtos {
  private ControlLifecycleDtos() {}

  /**
   * Staff backup-request intent. The opaque idempotency key is part of the signed body; operation type,
   * principal, state, lease details, and every execution value remain backend-owned.
   */
  public record BackupRequest(String idempotencyKey) {}

  /**
   * Executor completion intent. The executor presents its fencing token and one bounded terminal result
   * code. Principal identity, operation ownership, state, and lease validity remain backend-resolved.
   */
  public record CompleteRequest(Long fencingToken, String resultCode) {}

  /** Bounded operation view for the staff request/read routes. No internal id, hash, or fingerprints. */
  public record OperationView(
      String operationId,
      String operationType,
      String state,
      String resultCode,
      int attempt,
      Instant createdAt,
      Instant updatedAt) {}

  /** Executor lease grant. Carries exactly what the executor needs to later complete the operation. */
  public record LeaseResponse(
      String operationId,
      String operationType,
      long fencingToken,
      Instant leaseExpiresAt) {}

  /** Bounded terminal completion result for the executor. */
  public record CompletionResponse(String operationId, String state, String resultCode) {}

  /** Bounded, machine-readable error body (stable code only; never a raw message, entity, or secret). */
  public record ControlLifecycleError(String code) {}
}
