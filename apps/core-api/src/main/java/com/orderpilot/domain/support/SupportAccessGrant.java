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
 * OP-CAP-51 — a scoped, reasoned, expiring authorization for one staff principal to support exactly one
 * tenant for exactly one {@link StaffSupportScope}. This is the core of the support safety model:
 *
 * <ul>
 *   <li>a support case reference / reason is required (no anonymous access);</li>
 *   <li>the tenant scope is required and is the sole tenant this grant ever authorizes;</li>
 *   <li>{@code expiresAt} is mandatory and enforced deterministically — everything expires;</li>
 *   <li>a grant is usable only while {@code status == ACTIVE} and strictly before {@code expiresAt};</li>
 *   <li>there is no "all tenants" / permanent grant representation by construction.</li>
 * </ul>
 *
 * <p>The backend owns {@code status}, {@code expiresAt}, {@code createdAt}, and {@code createdBy}; a client
 * never supplies them.
 */
@Entity
@Table(name = "support_access_grant")
public class SupportAccessGrant {
  public enum Status {
    ACTIVE,
    REVOKED
  }

  /**
   * OP-CAP-52 — backend-owned approval state for the grant. A low-risk ({@code DIAGNOSTICS}) grant is
   * {@code AUTO_APPROVED} on creation and usable immediately; a sensitive grant is {@code PENDING_APPROVAL}
   * and is NOT usable until an approver moves it to {@code APPROVED}. A {@code REJECTED} grant can never
   * authorize access. The requester/creator can never set this from a request body.
   */
  public enum ApprovalStatus {
    AUTO_APPROVED,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED
  }

  /** Upper bound for the operator-supplied support case reference / reason (matches the V62 column width). */
  public static final int MAX_SUPPORT_CASE_REF_LENGTH = 200;
  /** Upper bound for an approver's decision note (matches the V63 column width). */
  public static final int MAX_DECISION_NOTE_LENGTH = 500;

  @Id @GeneratedValue private UUID id;
  @Column(name = "staff_user_id", nullable = false) private UUID staffUserId;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Enumerated(EnumType.STRING)
  @Column(name = "scope", nullable = false, length = 40) private StaffSupportScope scope;
  @Column(name = "support_case_ref", nullable = false, length = MAX_SUPPORT_CASE_REF_LENGTH) private String supportCaseRef;
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20) private Status status;
  @Enumerated(EnumType.STRING)
  @Column(name = "approval_status", nullable = false, length = 20) private ApprovalStatus approvalStatus;
  @Column(name = "expires_at", nullable = false) private Instant expiresAt;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "created_by") private UUID createdBy;
  @Column(name = "approved_by") private UUID approvedBy;
  @Column(name = "approval_decided_at") private Instant approvalDecidedAt;
  @Column(name = "approval_note", length = MAX_DECISION_NOTE_LENGTH) private String approvalNote;
  @Column(name = "revoked_at") private Instant revokedAt;
  @Column(name = "revoked_by") private UUID revokedBy;

  protected SupportAccessGrant() {}

  public SupportAccessGrant(
      UUID staffUserId,
      UUID tenantId,
      StaffSupportScope scope,
      String supportCaseRef,
      Instant expiresAt,
      UUID createdBy,
      Instant now) {
    this.staffUserId = staffUserId;
    this.tenantId = tenantId;
    this.scope = scope;
    this.supportCaseRef = supportCaseRef;
    this.status = Status.ACTIVE;
    // OP-CAP-52: a low-risk read-only scope is active immediately (OP-CAP-51 behavior preserved); a
    // sensitive scope is born PENDING_APPROVAL and cannot authorize access until explicitly approved.
    this.approvalStatus = scope != null && scope.isLowRiskAutoApprovable()
        ? ApprovalStatus.AUTO_APPROVED
        : ApprovalStatus.PENDING_APPROVAL;
    this.expiresAt = expiresAt;
    this.createdBy = createdBy;
    this.createdAt = now;
  }

  /** Deterministic expiry: a grant is valid only strictly before {@code expiresAt}. */
  public boolean isExpired(Instant now) {
    return !now.isBefore(expiresAt);
  }

  public boolean isRevoked() {
    return status == Status.REVOKED;
  }

  /** OP-CAP-52 — whether the approval gate is satisfied (auto-approved low-risk, or explicitly approved). */
  public boolean isApproved() {
    return approvalStatus == ApprovalStatus.AUTO_APPROVED || approvalStatus == ApprovalStatus.APPROVED;
  }

  public boolean isPendingApproval() {
    return approvalStatus == ApprovalStatus.PENDING_APPROVAL;
  }

  /** Usable only while ACTIVE, approved, and not yet expired. */
  public boolean isUsable(Instant now) {
    return status == Status.ACTIVE && isApproved() && !isExpired(now);
  }

  /**
   * OP-CAP-52 — approve a pending grant. Only a {@code PENDING_APPROVAL} grant can be approved; approving
   * any other state is a conflict the caller must reject. The approver/decision time are backend-owned.
   */
  public void approve(UUID approverId, String note, Instant now) {
    if (approvalStatus != ApprovalStatus.PENDING_APPROVAL) {
      throw new IllegalStateException("Grant is not pending approval");
    }
    this.approvalStatus = ApprovalStatus.APPROVED;
    this.approvedBy = approverId;
    this.approvalNote = note;
    this.approvalDecidedAt = now;
  }

  /** OP-CAP-52 — reject a pending grant; a rejected grant can never authorize access. */
  public void reject(UUID approverId, String note, Instant now) {
    if (approvalStatus != ApprovalStatus.PENDING_APPROVAL) {
      throw new IllegalStateException("Grant is not pending approval");
    }
    this.approvalStatus = ApprovalStatus.REJECTED;
    this.approvedBy = approverId;
    this.approvalNote = note;
    this.approvalDecidedAt = now;
  }

  /** First-write-wins revoke: an already-revoked grant is left untouched (idempotent). */
  public void revoke(UUID actorId, Instant now) {
    if (isRevoked()) {
      return;
    }
    this.status = Status.REVOKED;
    this.revokedAt = now;
    this.revokedBy = actorId;
  }

  public UUID getId() { return id; }
  public UUID getStaffUserId() { return staffUserId; }
  public UUID getTenantId() { return tenantId; }
  public StaffSupportScope getScope() { return scope; }
  public String getSupportCaseRef() { return supportCaseRef; }
  public Status getStatus() { return status; }
  public ApprovalStatus getApprovalStatus() { return approvalStatus; }
  public Instant getExpiresAt() { return expiresAt; }
  public Instant getCreatedAt() { return createdAt; }
  public UUID getCreatedBy() { return createdBy; }
  public UUID getApprovedBy() { return approvedBy; }
  public Instant getApprovalDecidedAt() { return approvalDecidedAt; }
  public String getApprovalNote() { return approvalNote; }
  public Instant getRevokedAt() { return revokedAt; }
  public UUID getRevokedBy() { return revokedBy; }
}
