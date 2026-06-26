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
    /**
     * Default for every request. Real execution is not implemented for this target — the execute path is a
     * stub that always fails closed. Every target EXCEPT the OP-CAP-54 bounded
     * {@link DataRepairTargetType#PROCESSING_JOB_STATUS_REPAIR} stays permanently here.
     */
    EXECUTION_DISABLED,
    /**
     * OP-CAP-54 — terminal success state set ONLY by the backend-owned processing-job status-repair
     * executor after an approved, deterministically-validated repair has mutated exactly one
     * {@code processing_job} row. The execution-result columns ({@code target_processing_job_id},
     * {@code previous_status}, {@code new_status}, {@code executed_at}, {@code executed_by}) are stamped
     * alongside it, and they make a repeated execute an idempotent replay (no second mutation).
     */
    EXECUTED
  }

  public static final int MAX_REASON_LENGTH = 500;
  public static final int MAX_TARGET_SUMMARY_LENGTH = 500;
  public static final int MAX_DECISION_NOTE_LENGTH = 500;
  public static final int MAX_STATUS_LENGTH = 40;

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
  // OP-CAP-54 — execution-result columns, stamped only by the processing-job status-repair executor on a
  // successful, approval-gated, deterministically-validated repair. They are the idempotency record: once
  // present (executionStatus == EXECUTED) a repeated execute is a replay that returns these values and
  // mutates nothing. No secret / raw payload / SQL is ever stored here — only safe operational metadata.
  @Column(name = "target_processing_job_id") private UUID targetProcessingJobId;
  @Column(name = "previous_status", length = MAX_STATUS_LENGTH) private String previousStatus;
  @Column(name = "new_status", length = MAX_STATUS_LENGTH) private String newStatus;
  @Column(name = "executed_at") private Instant executedAt;
  @Column(name = "executed_by") private UUID executedBy;
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

  /**
   * OP-CAP-54 — stamp the result of a successful processing-job status repair. Backend-owned: the executor
   * calls this only after the approval gate and the deterministic validator have passed and exactly one
   * {@code processing_job} row was mutated. It is idempotent at the entity level — a request that is already
   * {@link ExecutionStatus#EXECUTED} can never be re-executed (the executor replays the stored result
   * instead), so this guards against a double mutation.
   */
  public void recordProcessingJobRepairExecution(
      UUID processingJobId, String previousStatus, String newStatus, UUID executedBy, Instant now) {
    if (executionStatus == ExecutionStatus.EXECUTED) {
      throw new IllegalStateException("Data-repair request has already been executed");
    }
    this.executionStatus = ExecutionStatus.EXECUTED;
    this.targetProcessingJobId = processingJobId;
    this.previousStatus = previousStatus;
    this.newStatus = newStatus;
    this.executedBy = executedBy;
    this.executedAt = now;
  }

  public boolean isExecuted() {
    return executionStatus == ExecutionStatus.EXECUTED;
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
  public UUID getTargetProcessingJobId() { return targetProcessingJobId; }
  public String getPreviousStatus() { return previousStatus; }
  public String getNewStatus() { return newStatus; }
  public Instant getExecutedAt() { return executedAt; }
  public UUID getExecutedBy() { return executedBy; }
  public Instant getCreatedAt() { return createdAt; }
}
