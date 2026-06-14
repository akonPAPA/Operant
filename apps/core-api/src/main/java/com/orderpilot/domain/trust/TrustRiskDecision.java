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
 * Tenant-scoped, deterministic risk decision that combines document trust (17A), counterparty trust
 * (17B), payment obligation state (17C), and tenant policy into a single explainable outcome. Holds
 * the computed {@link TrustRiskLevel}, a 0..100 {@code riskScore}, the routing {@link TrustRiskAction},
 * the {@code blocking}/{@code humanReviewRequired} gate flags, and a bounded {@code reasonSummary}.
 *
 * <p>This is NOT a legal fraud verdict — it never claims a document is fake. It says "high-risk
 * signals detected; approval required before irreversible action." No raw document text, OCR text,
 * prompt text, bank credentials, or secrets are ever stored here.</p>
 */
@Entity
@Table(name = "trust_risk_decision")
public class TrustRiskDecision {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  /** Bounded token naming the business subject the decision is about (e.g. DOCUMENT, QUOTE, ORDER). */
  @Column(name = "subject_type", nullable = false, length = 32) private String subjectType;

  @Column(name = "subject_id", nullable = false) private UUID subjectId;

  @Column(name = "document_trust_run_id") private UUID documentTrustRunId;

  @Column(name = "counterparty_id") private UUID counterpartyId;

  @Column(name = "payment_obligation_id") private UUID paymentObligationId;

  @Column(name = "validation_run_id") private UUID validationRunId;

  /** Optional caller idempotency token; collapses repeat evaluations onto the active decision. */
  @Column(name = "idempotency_key", length = 120) private String idempotencyKey;

  @Enumerated(EnumType.STRING)
  @Column(name = "risk_level", nullable = false, length = 16) private TrustRiskLevel riskLevel;

  /** Numeric risk score clamped to 0..100. */
  @Column(name = "risk_score", nullable = false) private int riskScore;

  @Enumerated(EnumType.STRING)
  @Column(name = "action", nullable = false, length = 32) private TrustRiskAction action;

  @Column(name = "human_review_required", nullable = false) private boolean humanReviewRequired;

  @Column(name = "blocking", nullable = false) private boolean blocking;

  @Column(name = "signal_count", nullable = false) private int signalCount;

  /** Bounded, generic summary of the top reasons. Never raw document/OCR/prompt text. */
  @Column(name = "reason_summary", length = 280) private String reasonSummary;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16) private TrustRiskDecisionStatus status;

  @Column(name = "created_by") private UUID createdBy;

  @Column(name = "correlation_id", length = 120) private String correlationId;

  @Column(name = "created_at", nullable = false) private Instant createdAt;

  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected TrustRiskDecision() {}

  public TrustRiskDecision(
      UUID tenantId,
      String subjectType,
      UUID subjectId,
      UUID documentTrustRunId,
      UUID counterpartyId,
      UUID paymentObligationId,
      UUID validationRunId,
      String idempotencyKey,
      TrustRiskLevel riskLevel,
      int riskScore,
      TrustRiskAction action,
      boolean humanReviewRequired,
      boolean blocking,
      int signalCount,
      String reasonSummary,
      UUID createdBy,
      String correlationId,
      Instant now) {
    this.tenantId = tenantId;
    this.subjectType = subjectType;
    this.subjectId = subjectId;
    this.documentTrustRunId = documentTrustRunId;
    this.counterpartyId = counterpartyId;
    this.paymentObligationId = paymentObligationId;
    this.validationRunId = validationRunId;
    this.idempotencyKey = idempotencyKey;
    this.riskLevel = riskLevel;
    this.riskScore = riskScore;
    this.action = action;
    this.humanReviewRequired = humanReviewRequired;
    this.blocking = blocking;
    this.signalCount = signalCount;
    this.reasonSummary = reasonSummary;
    this.status = TrustRiskDecisionStatus.ACTIVE;
    this.createdBy = createdBy;
    this.correlationId = correlationId;
    this.createdAt = now;
    this.updatedAt = now;
  }

  /** Marks this decision superseded by a newer evaluation. */
  public void markSuperseded(Instant now) {
    this.status = TrustRiskDecisionStatus.SUPERSEDED;
    this.updatedAt = now;
  }

  /**
   * Applies a manual override in place: records the new effective level/action and marks the decision
   * OVERRIDDEN. Original contributions and the {@link TrustDecisionOverride} evidence are never deleted.
   */
  public void applyOverride(TrustRiskLevel newRiskLevel, TrustRiskAction newAction,
      boolean humanReviewRequired, boolean blocking, Instant now) {
    this.riskLevel = newRiskLevel;
    this.action = newAction;
    this.humanReviewRequired = humanReviewRequired;
    this.blocking = blocking;
    this.status = TrustRiskDecisionStatus.OVERRIDDEN;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public String getSubjectType() { return subjectType; }
  public UUID getSubjectId() { return subjectId; }
  public UUID getDocumentTrustRunId() { return documentTrustRunId; }
  public UUID getCounterpartyId() { return counterpartyId; }
  public UUID getPaymentObligationId() { return paymentObligationId; }
  public UUID getValidationRunId() { return validationRunId; }
  public String getIdempotencyKey() { return idempotencyKey; }
  public TrustRiskLevel getRiskLevel() { return riskLevel; }
  public int getRiskScore() { return riskScore; }
  public TrustRiskAction getAction() { return action; }
  public boolean isHumanReviewRequired() { return humanReviewRequired; }
  public boolean isBlocking() { return blocking; }
  public int getSignalCount() { return signalCount; }
  public String getReasonSummary() { return reasonSummary; }
  public TrustRiskDecisionStatus getStatus() { return status; }
  public UUID getCreatedBy() { return createdBy; }
  public String getCorrelationId() { return correlationId; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
