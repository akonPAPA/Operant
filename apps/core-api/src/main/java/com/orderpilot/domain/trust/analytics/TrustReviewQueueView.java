package com.orderpilot.domain.trust.analytics;

import com.orderpilot.domain.trust.TrustRiskAction;
import com.orderpilot.domain.trust.TrustRiskLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-17E Trust Analytics Read Models.
 *
 * Derived, rebuildable, tenant-scoped projection of one OP-CAP-17D {@code TrustRiskDecision} that belongs
 * in the operator review queue (HIGH/CRITICAL, blocking, human-review-required, or pending approval). The
 * operational decision remains the system of record; this row exists only for fast, bounded queue reads.
 * Unique per (tenant, trust risk decision) so projection is idempotent. No raw document/OCR/prompt text,
 * bank credentials, account numbers, or secrets are ever stored here.
 */
@Entity
@Table(name = "trust_review_queue_view",
    uniqueConstraints = @UniqueConstraint(name = "ux_trust_review_queue_view_decision",
        columnNames = {"tenant_id", "trust_risk_decision_id"}))
public class TrustReviewQueueView {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Column(name = "trust_risk_decision_id", nullable = false) private UUID trustRiskDecisionId;

  @Column(name = "subject_type", nullable = false, length = 32) private String subjectType;

  @Column(name = "subject_id", nullable = false) private UUID subjectId;

  @Column(name = "counterparty_id") private UUID counterpartyId;

  @Column(name = "document_trust_run_id") private UUID documentTrustRunId;

  @Column(name = "payment_obligation_id") private UUID paymentObligationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "risk_level", nullable = false, length = 16) private TrustRiskLevel riskLevel;

  @Column(name = "risk_score", nullable = false) private int riskScore;

  @Enumerated(EnumType.STRING)
  @Column(name = "action", nullable = false, length = 32) private TrustRiskAction action;

  @Column(name = "blocking", nullable = false) private boolean blocking;

  @Column(name = "human_review_required", nullable = false) private boolean humanReviewRequired;

  /** Latest approval requirement status (PENDING/SATISFIED/CANCELLED) or null when none applies. */
  @Column(name = "approval_status", length = 16) private String approvalStatus;

  @Column(name = "top_reason_code", length = 48) private String topReasonCode;

  @Column(name = "reason_summary", length = 280) private String reasonSummary;

  /** Source decision createdAt (preserved so the queue orders by the real decision time). */
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  /** Source decision updatedAt. */
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  @Column(name = "last_projected_at", nullable = false) private Instant lastProjectedAt;

  protected TrustReviewQueueView() {}

  public TrustReviewQueueView(UUID tenantId, UUID trustRiskDecisionId, Instant createdAt) {
    this.tenantId = tenantId;
    this.trustRiskDecisionId = trustRiskDecisionId;
    this.createdAt = createdAt;
  }

  /** Idempotent in-place refresh of all projected fields. */
  public void apply(String subjectType, UUID subjectId, UUID counterpartyId, UUID documentTrustRunId,
      UUID paymentObligationId, TrustRiskLevel riskLevel, int riskScore, TrustRiskAction action,
      boolean blocking, boolean humanReviewRequired, String approvalStatus, String topReasonCode,
      String reasonSummary, Instant createdAt, Instant updatedAt, Instant projectedAt) {
    this.subjectType = subjectType;
    this.subjectId = subjectId;
    this.counterpartyId = counterpartyId;
    this.documentTrustRunId = documentTrustRunId;
    this.paymentObligationId = paymentObligationId;
    this.riskLevel = riskLevel;
    this.riskScore = riskScore;
    this.action = action;
    this.blocking = blocking;
    this.humanReviewRequired = humanReviewRequired;
    this.approvalStatus = approvalStatus;
    this.topReasonCode = topReasonCode;
    this.reasonSummary = reasonSummary;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.lastProjectedAt = projectedAt;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getTrustRiskDecisionId() { return trustRiskDecisionId; }
  public String getSubjectType() { return subjectType; }
  public UUID getSubjectId() { return subjectId; }
  public UUID getCounterpartyId() { return counterpartyId; }
  public UUID getDocumentTrustRunId() { return documentTrustRunId; }
  public UUID getPaymentObligationId() { return paymentObligationId; }
  public TrustRiskLevel getRiskLevel() { return riskLevel; }
  public int getRiskScore() { return riskScore; }
  public TrustRiskAction getAction() { return action; }
  public boolean isBlocking() { return blocking; }
  public boolean isHumanReviewRequired() { return humanReviewRequired; }
  public String getApprovalStatus() { return approvalStatus; }
  public String getTopReasonCode() { return topReasonCode; }
  public String getReasonSummary() { return reasonSummary; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public Instant getLastProjectedAt() { return lastProjectedAt; }
}
