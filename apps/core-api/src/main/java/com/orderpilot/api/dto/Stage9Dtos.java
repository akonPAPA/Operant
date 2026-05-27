package com.orderpilot.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Stage9Dtos {
  private Stage9Dtos() {}

  public record Stage9IntegrationConnectionResponse(
      UUID id,
      String providerType,
      String displayName,
      String status,
      String mode,
      String connectionKind,
      String endpointRef,
      Instant lastSyncAt,
      Instant createdAt,
      Instant updatedAt
  ) {}

  public record Stage9DemoErpConnectionRequest(String displayName) {}

  public record Stage9ChangeRequestCreateRequest(
      String sourceType,
      UUID sourceId,
      String requestedAction,
      String requestPayloadJson,
      UUID actorId
  ) {}

  public record Stage9ApprovalRequest(UUID actorId, String reason) {}

  public record Stage9ChangeRequestResponse(
      UUID id,
      String status,
      String targetSystem,
      String targetEntity,
      String requestedAction,
      String sourceType,
      UUID sourceId,
      String validationStatus,
      String approvalStatus,
      String executionStatus,
      String externalReference,
      String failureReason,
      UUID createdByUserId,
      UUID approvedByUserId,
      Instant createdAt,
      Instant approvedAt,
      Instant rejectedAt,
      Instant executedAt,
      String connectorIdempotencyKeyHash,
      int connectorAttemptCount,
      int connectorMaxAttempts,
      Instant connectorLastAttemptAt,
      Instant connectorNextRetryAt,
      String connectorFailureType,
      boolean connectorRetryable
  ) {}

  public record Stage9ConnectorSyncRunResponse(
      UUID id,
      UUID integrationConnectionId,
      String providerType,
      String syncType,
      String direction,
      String status,
      int recordsRead,
      int recordsWritten,
      int recordsFailed,
      String errorCode,
      String errorMessage,
      Instant startedAt,
      Instant finishedAt
  ) {}

  public record Stage9ChangeRequestListResponse(List<Stage9ChangeRequestResponse> changeRequests) {}
  public record Stage9IntegrationListResponse(List<Stage9IntegrationConnectionResponse> integrations) {}
  public record Stage9ConnectorSyncRunListResponse(List<Stage9ConnectorSyncRunResponse> syncRuns) {}

  public record Stage9ConnectorPolicyResponse(
      String executionMode,
      List<String> capabilities,
      String credentialStatus,
      String maskedCredentialRef,
      boolean productionWritesEnabled,
      boolean networkCallsAllowed,
      String warning
  ) {}

  public record Stage9ExecutionSafetyResponse(
      UUID changeRequestId,
      String executionMode,
      List<String> capabilities,
      String connectorIdempotencyKeyHash,
      int attemptCount,
      int maxAttempts,
      Instant lastAttemptAt,
      Instant nextRetryAt,
      String failureType,
      String failureMessage,
      boolean retryable,
      boolean retryAllowed,
      boolean cancelAllowed,
      String credentialStatus,
      String maskedCredentialRef,
      boolean productionWritesEnabled,
      boolean networkCallsAllowed
  ) {}

  public record Stage9ConnectorAuditEventResponse(
      UUID id,
      String action,
      String entityType,
      String entityId,
      String metadata,
      Instant occurredAt
  ) {}

  public record Stage9ConnectorAuditResponse(List<Stage9ConnectorAuditEventResponse> events) {}
}
