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
 * OP-CAP-51 — a controlled data-repair request foundation. In this stage a request is <b>dry-run only</b>:
 * it captures intent (target area + reason) and produces a safe summary, but it never mutates any business
 * row. Real execution is intentionally not implemented and remains disabled.
 *
 * <ul>
 *   <li>{@code status} is {@code DRY_RUN_COMPLETED} once the safe summary is produced;</li>
 *   <li>{@code executionStatus} is permanently {@code EXECUTION_DISABLED} in this stage;</li>
 *   <li>there is no arbitrary SQL / script / raw target field — only a bounded {@link DataRepairTargetType}.</li>
 * </ul>
 */
@Entity
@Table(name = "data_repair_request")
public class DataRepairRequest {
  public enum Status {
    DRY_RUN_COMPLETED
  }

  public enum ExecutionStatus {
    /** Real execution is not implemented in this stage and may never be reached here. */
    EXECUTION_DISABLED
  }

  public static final int MAX_REASON_LENGTH = 500;

  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Enumerated(EnumType.STRING)
  @Column(name = "target_type", nullable = false, length = 40) private DataRepairTargetType targetType;
  @Column(name = "requested_by") private UUID requestedBy;
  @Column(name = "reason", nullable = false, length = MAX_REASON_LENGTH) private String reason;
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30) private Status status;
  @Enumerated(EnumType.STRING)
  @Column(name = "execution_status", nullable = false, length = 30) private ExecutionStatus executionStatus;
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected DataRepairRequest() {}

  public DataRepairRequest(
      UUID tenantId,
      DataRepairTargetType targetType,
      UUID requestedBy,
      String reason,
      Instant now) {
    this.tenantId = tenantId;
    this.targetType = targetType;
    this.requestedBy = requestedBy;
    this.reason = reason;
    this.status = Status.DRY_RUN_COMPLETED;
    this.executionStatus = ExecutionStatus.EXECUTION_DISABLED;
    this.createdAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public DataRepairTargetType getTargetType() { return targetType; }
  public UUID getRequestedBy() { return requestedBy; }
  public String getReason() { return reason; }
  public Status getStatus() { return status; }
  public ExecutionStatus getExecutionStatus() { return executionStatus; }
  public Instant getCreatedAt() { return createdAt; }
}
