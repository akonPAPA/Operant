package com.orderpilot.api.dto;

import java.time.Instant;
import java.util.UUID;

public final class Stage10CDtos {
  private Stage10CDtos() {}

  // OP-CAP-17F: the change-request creator is an authority field resolved server-side from the
  // trusted actor context (see ChangeRequestController#create / RequestActorResolver). It is never
  // accepted from the request body, so this DTO no longer carries a createdByUserId — the body holds
  // business intent only.
  public static record ChangeRequestCreateRequest(
      String targetSystem,
      String targetEntity,
      String requestedAction,
      String sourceType,
      UUID sourceId,
      UUID payloadSnapshotId,
      String requestPayloadJson,
      String idempotencyKey) {}

  // OP-CAP-17E: the approver is an authority field resolved server-side from the trusted actor
  // context (see ChangeRequestController#approve / RequestActorResolver); it is never accepted from
  // the request body, so no approval request DTO carries an approver id.

  public static record ChangeRequestRejectRequest(String reason) {}

  public static record ChangeRequestExecutionDisabledRequest(String reason) {}

  // OP-CAP-31: operator-safe default response. The raw external-write payload
  // (requestPayloadJson), the dedupe/integrity internals (idempotencyKey, payloadHash) and the
  // internal actor ids (createdByUserId, approvedByUserId) are NOT exposed on the default response.
  // Those belong on a separate admin/diagnostic DTO behind a stronger permission if ever needed.
  public static record ChangeRequestResponse(
      UUID id,
      String targetSystem,
      String targetEntity,
      String requestedAction,
      String sourceType,
      UUID sourceId,
      String validationStatus,
      String approvalStatus,
      String executionStatus,
      Instant createdAt,
      Instant validatedAt,
      Instant approvedAt,
      Instant rejectedAt,
      Instant executedAt,
      String externalReference,
      String failureReason,
      String cancellationReason) {}

  // OP-CAP-31: the raw outbox payload (payloadJson) is an internal external-write artifact and is
  // not exposed on the default operator response.
  public static record OutboxEventResponse(
      UUID id,
      String aggregateType,
      UUID aggregateId,
      String eventType,
      String status,
      Instant createdAt,
      Instant publishedAt,
      int attemptCount,
      String lastError) {}
}
