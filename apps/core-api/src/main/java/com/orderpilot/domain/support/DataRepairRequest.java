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
 * OP-CAP-51 / OP-CAP-52 — a controlled data-repair request. A request starts life as a <b>dry-run</b>: it
 * captures bounded intent (target area + reason) and produces a safe summary, but it never mutates any
 * business row. OP-CAP-52 adds an approval gate on top: a request can have execution approval REQUESTED,
 * then APPROVED or REJECTED by a separate approver. Even an approved request never executes in this stage —
 * {@code executionStatus} is permanently {@code EXECUTION_DISABLED} and the execute endpoint is a stub.
 *
 * <ul>
 *   <li>{@code status} is {@code DRY_RUN_COMPLETED} once the safe summary is produced;</li>
 *   <li>{@code approvalStatus} gates a future execution: NONE → PENDING_APPROVAL → APPROVED|REJECTED;</li>
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

  /**
   * OP-CAP-52 — backend-owned approval gate. {@code NONE} = dry-run only, no execution requested yet;
   * {@code PENDING_APPROVAL} = execution approval requested; {@code APPROVED} = an approver allowed
   * execution (which still cannot run in this stage); {@code REJECTED} = execution denied.
   */
  public enum ApprovalStatus {
    NONE,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED
  }

  public enum ExecutionStatus {
    /** Real execution is not implemented in this stage and may never be reached here. */
    EXECUTION_DISABLED
  }

  public static final int MAX_REASON_LENGTH = 500;
  public static final int MAX_TARGET_SUMMARY_LENGTH = 500;
  public static final int MAX_DECISION_NOTE_LENGTH = 500;

  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Enumerated(EnumType.STRING)
  @Column(name = "target_type", nullable = false, length = 40) private DataRepairTargetType targetType;
  @Column(name = "requested_by") private UUID requestedBy;
  @Column(name = "reason", nullable = false, length = MAX_REASON_LENGTH) private String reason;
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30) private Status status;
  @Enumerated(EnumType.STRING)
  @Column(name = "approval_status", nullable = false, length = 30) private ApprovalStatus approvalStatus;
  @Enumerated(EnumType.STRING)
  @Column(name = "execution_status", nullable = false, length = 30) private ExecutionStatus executionStatus;
  /** Backend-built operator-safe summary of the affected target, attached when approval is requested. */
  @Column(name = "affected_target_summary", length = MAX_TARGET_SUMMARY_LENGTH) private String affectedTargetSummary;
  @Column(name = "approved_by") private UUID approvedBy;
  @Column(name = "approval_note", length = MAX_DECISION_NOTE_LENGTH) private String approvalNote;
  @Column(name = "approval_requested_at") private Instant approvalRequestedAt;
  @Column(name = "approval_expires_at") private Instant approvalExpiresAt;
  @Column(name = "approval_decided_at") private Instant approvalDecidedAt;
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
    this.approvalStatus = ApprovalStatus.NONE;
    this.executionStatus = ExecutionStatus.EXECUTION_DISABLED;
    this.createdAt = now;
  }

  /**
   * OP-CAP-52 — request execution approval, attaching the backend-built affected-target summary and the
   * approval expiry. Only a request with no in-flight/decided approval may (re)request — a request that is
   * already pending or decided is a conflict the caller must reject.
   */
  public void requestApproval(String affectedTargetSummary, Instant expiresAt, Instant now) {
    if (approvalStatus != ApprovalStatus.NONE) {
      throw new IllegalStateException("Approval already requested or decided");
    }
    this.approvalStatus = ApprovalStatus.PENDING_APPROVAL;
    this.affectedTargetSummary = affectedTargetSummary;
    this.approvalRequestedAt = now;
    this.approvalExpiresAt = expiresAt;
  }

  public void approve(UUID approverId, String note, Instant now) {
    if (approvalStatus != ApprovalStatus.PENDING_APPROVAL) {
      throw new IllegalStateException("Request is not pending approval");
    }
    this.approvalStatus = ApprovalStatus.APPROVED;
    this.approvedBy = approverId;
    this.approvalNote = note;
    this.approvalDecidedAt = now;
  }

  public void reject(UUID approverId, String note, Instant now) {
    if (approvalStatus != ApprovalStatus.PENDING_APPROVAL) {
      throw new IllegalStateException("Request is not pending approval");
    }
    this.approvalStatus = ApprovalStatus.REJECTED;
    this.approvedBy = approverId;
    this.approvalNote = note;
    this.approvalDecidedAt = now;
  }

  public boolean isApproved() {
    return approvalStatus == ApprovalStatus.APPROVED;
  }

  public boolean isApprovalExpired(Instant now) {
    return approvalExpiresAt != null && !now.isBefore(approvalExpiresAt);
  }

  /**
   * OP-CAP-52 — whether an execution attempt could even be considered. True only when an approval has been
   * granted and has not expired. Note this NEVER means execution runs — execution stays disabled — it only
   * gates which safe response (denied vs execution-disabled) the stub returns.
   */
  public boolean isExecutionApprovalValid(Instant now) {
    return isApproved() && !isApprovalExpired(now);
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public DataRepairTargetType getTargetType() { return targetType; }
  public UUID getRequestedBy() { return requestedBy; }
  public String getReason() { return reason; }
  public Status getStatus() { return status; }
  public ApprovalStatus getApprovalStatus() { return approvalStatus; }
  public ExecutionStatus getExecutionStatus() { return executionStatus; }
  public String getAffectedTargetSummary() { return affectedTargetSummary; }
  public UUID getApprovedBy() { return approvedBy; }
  public String getApprovalNote() { return approvalNote; }
  public Instant getApprovalRequestedAt() { return approvalRequestedAt; }
  public Instant getApprovalExpiresAt() { return approvalExpiresAt; }
  public Instant getApprovalDecidedAt() { return approvalDecidedAt; }
  public Instant getCreatedAt() { return createdAt; }
}
