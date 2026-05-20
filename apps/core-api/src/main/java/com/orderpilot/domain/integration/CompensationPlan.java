package com.orderpilot.domain.integration;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "compensation_plan")
public class CompensationPlan {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "source_change_request_id") private UUID sourceChangeRequestId;
  @Column(name = "connector_command_id") private UUID connectorCommandId;
  @Column(name = "target_system_type", nullable = false) private String targetSystemType;
  @Column(name = "target_external_reference") private String targetExternalReference;
  @Enumerated(EnumType.STRING) @Column(name = "compensation_action_type", nullable = false) private CompensationActionType compensationActionType;
  @Enumerated(EnumType.STRING) @Column(name = "reason_code", nullable = false) private CompensationReasonCode reasonCode;
  @Column(name = "human_readable_reason", nullable = false) private String humanReadableReason;
  @Column(name = "requires_human_approval", nullable = false) private boolean requiresHumanApproval;
  @Column(name = "safe_to_auto_execute", nullable = false) private boolean safeToAutoExecute;
  @Enumerated(EnumType.STRING) @Column(nullable = false) private CompensationPlanStatus status;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "created_by") private UUID createdBy;
  @Column(name = "audit_correlation_id", nullable = false) private UUID auditCorrelationId;

  protected CompensationPlan() {}

  public CompensationPlan(UUID tenantId, UUID sourceChangeRequestId, UUID connectorCommandId, String targetSystemType, String targetExternalReference, CompensationActionType compensationActionType, CompensationReasonCode reasonCode, String humanReadableReason, boolean requiresHumanApproval, CompensationPlanStatus status, Instant now, UUID createdBy, UUID auditCorrelationId) {
    this.tenantId = tenantId;
    this.sourceChangeRequestId = sourceChangeRequestId;
    this.connectorCommandId = connectorCommandId;
    this.targetSystemType = targetSystemType;
    this.targetExternalReference = targetExternalReference;
    this.compensationActionType = compensationActionType;
    this.reasonCode = reasonCode;
    this.humanReadableReason = humanReadableReason;
    this.requiresHumanApproval = requiresHumanApproval;
    this.safeToAutoExecute = false;
    this.status = status;
    this.createdAt = now;
    this.createdBy = createdBy;
    this.auditCorrelationId = auditCorrelationId == null ? UUID.randomUUID() : auditCorrelationId;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getSourceChangeRequestId() { return sourceChangeRequestId; }
  public UUID getConnectorCommandId() { return connectorCommandId; }
  public String getTargetSystemType() { return targetSystemType; }
  public String getTargetExternalReference() { return targetExternalReference; }
  public CompensationActionType getCompensationActionType() { return compensationActionType; }
  public CompensationReasonCode getReasonCode() { return reasonCode; }
  public String getHumanReadableReason() { return humanReadableReason; }
  public boolean isRequiresHumanApproval() { return requiresHumanApproval; }
  public boolean isSafeToAutoExecute() { return safeToAutoExecute; }
  public CompensationPlanStatus getStatus() { return status; }
  public Instant getCreatedAt() { return createdAt; }
  public UUID getCreatedBy() { return createdBy; }
  public UUID getAuditCorrelationId() { return auditCorrelationId; }
}
