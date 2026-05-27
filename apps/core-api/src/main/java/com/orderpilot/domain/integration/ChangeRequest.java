package com.orderpilot.domain.integration;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "change_request",
    uniqueConstraints = @UniqueConstraint(name = "uq_change_request_tenant_idempotency_key_entity", columnNames = {"tenant_id", "idempotency_key"}))
public class ChangeRequest {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "target_system", nullable = false) private String targetSystem;
  @Column(name = "target_entity", nullable = false) private String targetEntity;
  @Column(name = "requested_action", nullable = false) private String requestedAction;
  @Column(name = "source_type", nullable = false) private String sourceType;
  @Column(name = "source_id", nullable = false) private UUID sourceId;
  @Column(name = "payload_snapshot_id") private UUID payloadSnapshotId;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "request_payload_json", columnDefinition = "jsonb", nullable = false) private String requestPayloadJson;
  @Column(name = "validation_status", nullable = false) private String validationStatus;
  @Column(name = "approval_status", nullable = false) private String approvalStatus;
  @Column(name = "execution_status", nullable = false) private String executionStatus;
  @Column(name = "idempotency_key") private String idempotencyKey;
  @Column(name = "payload_hash") private String payloadHash;
  @Column(name = "created_by_user_id") private UUID createdByUserId;
  @Column(name = "approved_by_user_id") private UUID approvedByUserId;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "validated_at") private Instant validatedAt;
  @Column(name = "approved_at") private Instant approvedAt;
  @Column(name = "rejected_at") private Instant rejectedAt;
  @Column(name = "executed_at") private Instant executedAt;
  @Column(name = "external_reference") private String externalReference;
  @Column(name = "failure_reason") private String failureReason;
  @Column(name = "cancellation_reason") private String cancellationReason;
  @Column(name = "connector_idempotency_key") private String connectorIdempotencyKey;
  @Column(name = "connector_attempt_count", nullable = false) private int connectorAttemptCount;
  @Column(name = "connector_max_attempts", nullable = false) private int connectorMaxAttempts;
  @Column(name = "connector_last_attempt_at") private Instant connectorLastAttemptAt;
  @Column(name = "connector_next_retry_at") private Instant connectorNextRetryAt;
  @Enumerated(EnumType.STRING) @Column(name = "connector_failure_type") private ConnectorFailureType connectorFailureType;
  @Column(name = "connector_retryable", nullable = false) private boolean connectorRetryable;

  protected ChangeRequest() {}

  public ChangeRequest(UUID tenantId, String targetSystem, String targetEntity, String requestedAction, String sourceType, UUID sourceId, String requestPayloadJson, String idempotencyKey, UUID createdByUserId, Instant now) {
    this.tenantId = tenantId;
    this.targetSystem = targetSystem;
    this.targetEntity = targetEntity;
    this.requestedAction = requestedAction;
    this.sourceType = sourceType;
    this.sourceId = sourceId;
    this.requestPayloadJson = requestPayloadJson == null || requestPayloadJson.isBlank() ? "{}" : requestPayloadJson;
    this.validationStatus = "PENDING_VALIDATION";
    this.approvalStatus = "PENDING_APPROVAL";
    this.executionStatus = "EXECUTION_DISABLED";
    this.connectorMaxAttempts = 3;
    this.connectorAttemptCount = 0;
    this.connectorRetryable = false;
    this.idempotencyKey = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey;
    this.createdByUserId = createdByUserId;
    this.createdAt = now;
  }

  public static ChangeRequest stage11eDraft(UUID tenantId, String targetSystem, String targetEntity, String requestedAction, String sourceType, UUID sourceId, UUID payloadSnapshotId, String requestPayloadJson, String idempotencyKey, String payloadHash, UUID createdByUserId, Instant now) {
    ChangeRequest request = new ChangeRequest(tenantId, targetSystem, targetEntity, requestedAction, sourceType, sourceId, requestPayloadJson, idempotencyKey, createdByUserId, now);
    request.payloadSnapshotId = payloadSnapshotId;
    request.payloadHash = payloadHash;
    request.validationStatus = "VALID";
    request.approvalStatus = "DRAFT";
    request.executionStatus = "NOT_EXECUTED";
    request.validatedAt = now;
    return request;
  }

  public void markValidated(Instant now) {
    this.validationStatus = "VALIDATED";
    this.validatedAt = now;
    this.failureReason = null;
  }

  public void markValidationFailed(String reason, Instant now) {
    this.validationStatus = "VALIDATION_FAILED";
    this.validatedAt = now;
    this.failureReason = reason;
  }

  public void approve(UUID approvedByUserId, Instant now) {
    this.approvalStatus = "APPROVED";
    this.approvedByUserId = approvedByUserId;
    this.approvedAt = now;
    this.executionStatus = "EXECUTION_DISABLED";
  }

  public void reject(String reason, Instant now) {
    this.approvalStatus = "REJECTED";
    this.rejectedAt = now;
    this.executionStatus = "NOT_EXECUTABLE";
    this.failureReason = reason;
  }

  public void markExecutionDisabled(String reason) {
    this.executionStatus = "EXECUTION_DISABLED";
    this.executedAt = null;
    this.externalReference = null;
    this.failureReason = reason;
  }

  public void markExecutionPending(Instant now) {
    this.executionStatus = "EXECUTION_PENDING";
    this.failureReason = null;
    this.connectorLastAttemptAt = now;
    this.connectorAttemptCount = this.connectorAttemptCount + 1;
    this.connectorRetryable = false;
    this.connectorNextRetryAt = null;
    this.connectorFailureType = null;
  }

  public void markExecuted(String externalReference, String connectorIdempotencyKey, Instant now) {
    this.executionStatus = "EXECUTED";
    this.externalReference = externalReference;
    this.connectorIdempotencyKey = connectorIdempotencyKey;
    this.executedAt = now;
    this.failureReason = null;
    this.connectorRetryable = false;
    this.connectorNextRetryAt = null;
    this.connectorFailureType = null;
  }

  public void markExecutionFailed(ConnectorFailureType failureType, String reason, boolean retryable, Instant nextRetryAt, Instant now) {
    this.executionStatus = "FAILED";
    this.failureReason = safe(reason);
    this.executedAt = now;
    this.connectorFailureType = failureType == null ? ConnectorFailureType.UNKNOWN : failureType;
    this.connectorRetryable = retryable && this.connectorAttemptCount < this.connectorMaxAttempts;
    this.connectorNextRetryAt = this.connectorRetryable ? nextRetryAt : null;
  }

  public void cancelExecution(String reason, Instant now) {
    this.approvalStatus = "CANCELLED";
    this.executionStatus = "CANCELLED";
    this.cancellationReason = safe(reason);
    this.failureReason = safe(reason);
    this.connectorRetryable = false;
    this.connectorNextRetryAt = null;
    this.rejectedAt = now;
  }

  public void approveInternal(UUID approvedByUserId, Instant now) {
    this.approvalStatus = "APPROVED_INTERNAL";
    this.approvedByUserId = approvedByUserId;
    this.approvedAt = now;
    this.executionStatus = "EXECUTION_DISABLED";
    this.executedAt = null;
    this.externalReference = null;
  }

  public void cancel(String reason, Instant now) {
    this.approvalStatus = "CANCELLED";
    this.executionStatus = "EXECUTION_DISABLED";
    this.cancellationReason = reason;
    this.failureReason = reason;
    this.rejectedAt = now;
    this.executedAt = null;
    this.externalReference = null;
  }

  public void block(String reason, Instant now) {
    this.validationStatus = "BLOCKED";
    this.executionStatus = "EXECUTION_DISABLED";
    this.failureReason = reason;
    this.validatedAt = now;
    this.executedAt = null;
    this.externalReference = null;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public String getTargetSystem() { return targetSystem; }
  public String getTargetEntity() { return targetEntity; }
  public String getRequestedAction() { return requestedAction; }
  public String getSourceType() { return sourceType; }
  public UUID getSourceId() { return sourceId; }
  public UUID getPayloadSnapshotId() { return payloadSnapshotId; }
  public String getRequestPayloadJson() { return requestPayloadJson; }
  public String getValidationStatus() { return validationStatus; }
  public String getApprovalStatus() { return approvalStatus; }
  public String getExecutionStatus() { return executionStatus; }
  public String getIdempotencyKey() { return idempotencyKey; }
  public String getPayloadHash() { return payloadHash; }
  public UUID getCreatedByUserId() { return createdByUserId; }
  public UUID getApprovedByUserId() { return approvedByUserId; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getValidatedAt() { return validatedAt; }
  public Instant getApprovedAt() { return approvedAt; }
  public Instant getRejectedAt() { return rejectedAt; }
  public Instant getExecutedAt() { return executedAt; }
  public String getExternalReference() { return externalReference; }
  public String getFailureReason() { return failureReason; }
  public String getCancellationReason() { return cancellationReason; }
  public String getConnectorIdempotencyKey() { return connectorIdempotencyKey; }
  public int getConnectorAttemptCount() { return connectorAttemptCount; }
  public int getConnectorMaxAttempts() { return connectorMaxAttempts; }
  public Instant getConnectorLastAttemptAt() { return connectorLastAttemptAt; }
  public Instant getConnectorNextRetryAt() { return connectorNextRetryAt; }
  public ConnectorFailureType getConnectorFailureType() { return connectorFailureType; }
  public boolean isConnectorRetryable() { return connectorRetryable; }

  private static String safe(String value) {
    if (value == null || value.isBlank()) return null;
    return value.replaceAll("(?i)(secret|token|password|key)=[^,\\s}]+", "$1=REDACTED");
  }
}
