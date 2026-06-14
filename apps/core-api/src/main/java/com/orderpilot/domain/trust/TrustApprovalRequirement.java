package com.orderpilot.domain.trust;

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
 * OP-CAP-17D Trust Risk Decision Engine.
 *
 * Tenant-scoped record that a {@link TrustRiskDecision} requires human approval before an irreversible
 * action (commit/export/finalization) may proceed. Created for HIGH/CRITICAL decisions and when tenant
 * policy forces approval. The backend command service that performs the irreversible action is
 * responsible for checking that no {@code PENDING} requirement remains.
 */
@Entity
@Table(name = "trust_approval_requirement")
public class TrustApprovalRequirement {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Column(name = "trust_risk_decision_id", nullable = false) private UUID trustRiskDecisionId;

  @Enumerated(EnumType.STRING)
  @Column(name = "required_action", nullable = false, length = 32) private TrustRiskAction requiredAction;

  /** Bounded permission code the approver must hold (e.g. REVIEW_ACTION / TRUST_RISK_OVERRIDE). */
  @Column(name = "required_permission_code", length = 48) private String requiredPermissionCode;

  /** Optional bounded role code the approver must hold. */
  @Column(name = "required_role_code", length = 48) private String requiredRoleCode;

  @Enumerated(EnumType.STRING)
  @Column(name = "reason_code", nullable = false, length = 48) private TrustRiskReasonCode reasonCode;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16) private TrustApprovalStatus status;

  @Column(name = "created_at", nullable = false) private Instant createdAt;

  @Column(name = "satisfied_at") private Instant satisfiedAt;

  @Column(name = "satisfied_by") private UUID satisfiedBy;

  protected TrustApprovalRequirement() {}

  public TrustApprovalRequirement(UUID tenantId, UUID trustRiskDecisionId, TrustRiskAction requiredAction,
      String requiredPermissionCode, String requiredRoleCode, TrustRiskReasonCode reasonCode, Instant now) {
    this.tenantId = tenantId;
    this.trustRiskDecisionId = trustRiskDecisionId;
    this.requiredAction = requiredAction;
    this.requiredPermissionCode = requiredPermissionCode;
    this.requiredRoleCode = requiredRoleCode;
    this.reasonCode = reasonCode;
    this.status = TrustApprovalStatus.PENDING;
    this.createdAt = now;
  }

  public void satisfy(UUID actor, Instant now) {
    this.status = TrustApprovalStatus.SATISFIED;
    this.satisfiedAt = now;
    this.satisfiedBy = actor;
  }

  public void cancel(Instant now) {
    this.status = TrustApprovalStatus.CANCELLED;
    this.satisfiedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getTrustRiskDecisionId() { return trustRiskDecisionId; }
  public TrustRiskAction getRequiredAction() { return requiredAction; }
  public String getRequiredPermissionCode() { return requiredPermissionCode; }
  public String getRequiredRoleCode() { return requiredRoleCode; }
  public TrustRiskReasonCode getReasonCode() { return reasonCode; }
  public TrustApprovalStatus getStatus() { return status; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getSatisfiedAt() { return satisfiedAt; }
  public UUID getSatisfiedBy() { return satisfiedBy; }
}
