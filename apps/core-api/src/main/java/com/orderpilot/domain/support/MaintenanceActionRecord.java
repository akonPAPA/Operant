package com.orderpilot.domain.support;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-51 — an immutable audit/record of a maintenance/update action taken by owner-company staff for a
 * tenant. This is a record only: persisting it never triggers a deployment, never executes a migration, and
 * never calls an external system. {@code status} is always {@code RECORDED} in this stage — there is no
 * execution lifecycle.
 */
@Entity
@Table(name = "maintenance_action_record")
public class MaintenanceActionRecord {
  public enum Status {
    RECORDED
  }

  public static final int MAX_REASON_LENGTH = 500;
  public static final int MAX_TARGET_SCOPE_LENGTH = 120;

  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Enumerated(EnumType.STRING)
  @Column(name = "action_type", nullable = false, length = 40) private MaintenanceActionType actionType;
  @Column(name = "staff_user_id") private UUID staffUserId;
  @Column(name = "reason", nullable = false, length = MAX_REASON_LENGTH) private String reason;
  @Column(name = "target_scope", nullable = false, length = MAX_TARGET_SCOPE_LENGTH) private String targetScope;
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20) private Status status;
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected MaintenanceActionRecord() {}

  public MaintenanceActionRecord(
      UUID tenantId,
      MaintenanceActionType actionType,
      UUID staffUserId,
      String reason,
      String targetScope,
      Instant now) {
    this.tenantId = tenantId;
    this.actionType = actionType;
    this.staffUserId = staffUserId;
    this.reason = reason;
    this.targetScope = targetScope;
    this.status = Status.RECORDED;
    this.createdAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public MaintenanceActionType getActionType() { return actionType; }
  public UUID getStaffUserId() { return staffUserId; }
  public String getReason() { return reason; }
  public String getTargetScope() { return targetScope; }
  public Status getStatus() { return status; }
  public Instant getCreatedAt() { return createdAt; }
}
