package com.orderpilot.api.dto;

import java.time.Instant;
import java.util.UUID;

public final class Stage10CDtos {
  private Stage10CDtos() {}

  public static record ChangeRequestCreateRequest(
      String targetSystem,
      String targetEntity,
      String requestedAction,
      String sourceType,
      UUID sourceId,
      UUID payloadSnapshotId,
      String requestPayloadJson,
      String idempotencyKey,
      UUID createdByUserId) {}

  public static record ChangeRequestApprovalRequest(UUID approvedByUserId) {}

  public static record ChangeRequestRejectRequest(String reason) {}

  public static record ChangeRequestExecutionDisabledRequest(String reason) {}

  public static record ChangeRequestResponse(
      UUID id,
      String targetSystem,
      String targetEntity,
      String requestedAction,
      String sourceType,
      UUID sourceId,
      String requestPayloadJson,
      String validationStatus,
      String approvalStatus,
      String executionStatus,
      String idempotencyKey,
      String payloadHash,
      UUID createdByUserId,
      UUID approvedByUserId,
      Instant createdAt,
      Instant validatedAt,
      Instant approvedAt,
      Instant rejectedAt,
      Instant executedAt,
      String externalReference,
      String failureReason,
      String cancellationReason) {}

  public static record OutboxEventResponse(
      UUID id,
      String aggregateType,
      UUID aggregateId,
      String eventType,
      String payloadJson,
      String status,
      Instant createdAt,
      Instant publishedAt,
      int attemptCount,
      String lastError) {}
}
