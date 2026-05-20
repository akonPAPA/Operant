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
}
