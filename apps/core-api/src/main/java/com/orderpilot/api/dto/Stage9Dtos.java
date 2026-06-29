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
      Instant lastSyncAt,
      Instant createdAt,
      Instant updatedAt
  ) {}

  public record Stage9DemoErpConnectionRequest(String displayName) {}

  // OP-CAP-17F / Wave 01H Category C: the connector change-request creator is an authority field
  // resolved server-side from the trusted actor context (see Stage9IntegrationController#createChangeRequest /
  // RequestActorResolver). The external-write payload (requestPayloadJson) is lower-layer internal
  // state — a client-supplied value could steer demo execution (e.g. simulateFailure) — so it is NOT
  // accepted from the request body; the backend builds the canonical payload. The body holds business
  // intent only.
  public record Stage9ChangeRequestCreateRequest(
      String sourceType,
      UUID sourceId,
      String requestedAction
  ) {}

  // OP-CAP-32: reject/cancel carry business intent only (the operator's reason). The acting user is
  // backend-owned authority resolved server-side from the trusted actor context (see
  // Stage9IntegrationController / RequestActorResolver), never from the request body — so this DTO
  // no longer carries an actorId. A body-supplied actorId is ignored (unknown property).
  public record Stage9ApprovalRequest(String reason) {}

  // Wave 01H Category D: operator-safe connector change-request response. The internal connector
  // execution machinery (executionStatus, connectorFailureType, connectorRetryable) is not exposed;
  // the business-facing rollup `status` already conveys execution readiness/outcome in safe terms.
  public record Stage9ChangeRequestResponse(
      UUID id,
      String status,
      String targetSystem,
      String targetEntity,
      String requestedAction,
      String sourceType,
      String validationStatus,
      String approvalStatus,
      String externalReference,
      String failureReason,
      Instant createdAt,
      Instant approvedAt,
      Instant rejectedAt,
      Instant executedAt
  ) {}

  public record Stage9ConnectorSyncRunResponse(
      UUID id,
      String providerType,
      String syncType,
      String direction,
      String status,
      int recordsRead,
      int recordsWritten,
      int recordsFailed,
      String errorCode,
      Instant startedAt,
      Instant finishedAt
  ) {}

  public record Stage9ChangeRequestListResponse(List<Stage9ChangeRequestResponse> changeRequests) {}
  public record Stage9IntegrationListResponse(List<Stage9IntegrationConnectionResponse> integrations) {}
  public record Stage9ConnectorSyncRunListResponse(List<Stage9ConnectorSyncRunResponse> syncRuns) {}

  public record Stage9ConnectorPolicyResponse(
      String executionMode,
      boolean productionWritesEnabled,
      boolean networkCallsAllowed,
      String warning
  ) {}

  public record Stage9ExecutionSafetyResponse(
      String executionMode,
      int attemptCount,
      int maxAttempts,
      Instant lastAttemptAt,
      Instant nextRetryAt,
      String failureType,
      boolean retryable,
      boolean retryAllowed,
      boolean cancelAllowed,
      boolean productionWritesEnabled,
      boolean networkCallsAllowed
  ) {}

  public record Stage9ConnectorAuditEventResponse(
      String action,
      String entityType,
      Instant occurredAt
  ) {}

  public record Stage9ConnectorAuditResponse(List<Stage9ConnectorAuditEventResponse> events) {}
}
