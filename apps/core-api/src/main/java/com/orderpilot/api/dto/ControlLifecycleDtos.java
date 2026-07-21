package com.orderpilot.api.dto;

import java.time.Instant;

/**
 * P1-E2A - bounded request/response contracts for the durable backup-operation control slice.
 *
 * <p>The request DTOs declare NO authority fields. Following the app-wide Jackson contract (unknown
 * properties are ignored, not deserialized), this is the structural guard that a client cannot influence
 * behaviour by smuggling operation state, executor identity, a fencing token onto a staff route, or a
 * path/command/database/container/image/environment value: none of those are declared, so any such value
 * in the body is ignored and never reaches the service. Authority is resolved by the backend (operation
 * type from the route, principal from the verified control credential, idempotency key from the header).
 *
 * <p>Response DTOs expose only bounded, operator/executor-safe fields. They never expose the internal
 * UUID primary key, the idempotency-key hash, principal fingerprints, or any persistence/security
 * internal.
 */
public final class ControlLifecycleDtos {
  private ControlLifecycleDtos() {}

  /**
   * Backup request body. It carries NO fields: the operation type is fixed by the route, the idempotency
   * key comes from the {@code Idempotency-Key} header, and every other value is backend-resolved. An empty
   * object is valid; any provided field is ignored and has no effect.
   */
  public record BackupRequest() {}

  /**
   * Executor completion body. The executor presents its fencing token (proving it holds the current
   * lease) and a bounded terminal result code. Any other field in the body is ignored.
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
