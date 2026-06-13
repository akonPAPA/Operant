package com.orderpilot.domain.trust;

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
 * OP-CAP-17B Counterparty Trust Profile Foundation.
 *
 * Current tenant-scoped trust state for one counterparty (customer account). Holds deterministic
 * scores, the last trust decision snapshot, and bounded behaviour counters. Business counters use
 * {@code long} to avoid overflow. No raw document text, raw bank credentials, account numbers, or
 * prompt text are ever stored here. Unique per (tenant, customer account).
 */
@Entity
@Table(name = "counterparty_trust_profile",
    uniqueConstraints = @UniqueConstraint(name = "ux_counterparty_trust_profile_tenant_account",
        columnNames = {"tenant_id", "customer_account_id"}))
public class CounterpartyTrustProfile {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Column(name = "customer_account_id", nullable = false) private UUID customerAccountId;

  @Column(name = "trust_score", nullable = false) private int trustScore;

  @Enumerated(EnumType.STRING)
  @Column(name = "trust_tier", nullable = false, length = 16) private TrustTier trustTier;

  @Column(name = "document_reliability_score", nullable = false) private int documentReliabilityScore;

  @Column(name = "payment_reliability_score", nullable = false) private int paymentReliabilityScore;

  @Column(name = "order_pattern_score", nullable = false) private int orderPatternScore;

  @Column(name = "total_document_count", nullable = false) private long totalDocumentCount;

  @Column(name = "high_risk_document_count", nullable = false) private long highRiskDocumentCount;

  @Column(name = "critical_risk_document_count", nullable = false) private long criticalRiskDocumentCount;

  @Column(name = "warning_document_count", nullable = false) private long warningDocumentCount;

  @Column(name = "manual_review_count", nullable = false) private long manualReviewCount;

  @Column(name = "approved_override_count", nullable = false) private long approvedOverrideCount;

  @Column(name = "rejected_document_count", nullable = false) private long rejectedDocumentCount;

  @Column(name = "disputed_count", nullable = false) private long disputedCount;

  @Column(name = "completed_order_count", nullable = false) private long completedOrderCount;

  /** Placeholder counter for OP-CAP-17C payment obligation intelligence. */
  @Column(name = "overdue_payment_count", nullable = false) private long overduePaymentCount;

  @Column(name = "bank_account_change_count", nullable = false) private long bankAccountChangeCount;

  @Column(name = "last_document_trust_run_id") private UUID lastDocumentTrustRunId;

  @Enumerated(EnumType.STRING)
  @Column(name = "last_risk_level", length = 16) private TrustRiskLevel lastRiskLevel;

  @Column(name = "last_trust_signal_at") private Instant lastTrustSignalAt;

  @Column(name = "last_order_at") private Instant lastOrderAt;

  @Column(name = "last_payment_at") private Instant lastPaymentAt;

  @Column(name = "created_at", nullable = false) private Instant createdAt;

  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected CounterpartyTrustProfile() {}

  public CounterpartyTrustProfile(UUID tenantId, UUID customerAccountId, Instant now) {
    this.tenantId = tenantId;
    this.customerAccountId = customerAccountId;
    // New/unknown profile baseline; the scoring service overwrites these on first activity.
    this.trustScore = 50;
    this.trustTier = TrustTier.UNKNOWN;
    this.documentReliabilityScore = 50;
    this.paymentReliabilityScore = 50;
    this.orderPatternScore = 50;
    this.createdAt = now;
    this.updatedAt = now;
  }

  /** Records one completed document trust run by risk level. Counters saturate-safe via long. */
  public void recordDocumentRisk(TrustRiskLevel level, UUID documentTrustRunId, Instant now) {
    this.totalDocumentCount += 1;
    if (level == TrustRiskLevel.CRITICAL) {
      this.criticalRiskDocumentCount += 1;
    } else if (level == TrustRiskLevel.HIGH) {
      this.highRiskDocumentCount += 1;
    } else if (level == TrustRiskLevel.MEDIUM) {
      this.warningDocumentCount += 1;
    }
    this.lastDocumentTrustRunId = documentTrustRunId;
    this.lastRiskLevel = level;
    this.lastTrustSignalAt = now;
    this.updatedAt = now;
  }

  public void incrementBankAccountChange(Instant now) { this.bankAccountChangeCount += 1; this.updatedAt = now; }
  public void incrementManualReview(Instant now) { this.manualReviewCount += 1; this.updatedAt = now; }
  public void incrementApprovedOverride(Instant now) { this.approvedOverrideCount += 1; this.updatedAt = now; }
  public void incrementRejectedDocument(Instant now) { this.rejectedDocumentCount += 1; this.updatedAt = now; }
  public void incrementDisputed(Instant now) { this.disputedCount += 1; this.updatedAt = now; }
  public void recordCompletedOrder(Instant now) { this.completedOrderCount += 1; this.lastOrderAt = now; this.updatedAt = now; }
  public void incrementOverduePayment(Instant now) { this.overduePaymentCount += 1; this.updatedAt = now; }

  public void noteRiskLevel(TrustRiskLevel level, Instant now) {
    this.lastRiskLevel = level;
    this.lastTrustSignalAt = now;
    this.updatedAt = now;
  }

  /** Applies recomputed deterministic scores. */
  public void applyScores(int trustScore, TrustTier trustTier, int documentReliabilityScore,
      int paymentReliabilityScore, int orderPatternScore, Instant now) {
    this.trustScore = trustScore;
    this.trustTier = trustTier;
    this.documentReliabilityScore = documentReliabilityScore;
    this.paymentReliabilityScore = paymentReliabilityScore;
    this.orderPatternScore = orderPatternScore;
    this.updatedAt = now;
  }

  public boolean hasActivity() {
    return totalDocumentCount > 0 || completedOrderCount > 0 || manualReviewCount > 0
        || disputedCount > 0 || rejectedDocumentCount > 0 || bankAccountChangeCount > 0
        || overduePaymentCount > 0 || lastRiskLevel != null;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getCustomerAccountId() { return customerAccountId; }
  public int getTrustScore() { return trustScore; }
  public TrustTier getTrustTier() { return trustTier; }
  public int getDocumentReliabilityScore() { return documentReliabilityScore; }
  public int getPaymentReliabilityScore() { return paymentReliabilityScore; }
  public int getOrderPatternScore() { return orderPatternScore; }
  public long getTotalDocumentCount() { return totalDocumentCount; }
  public long getHighRiskDocumentCount() { return highRiskDocumentCount; }
  public long getCriticalRiskDocumentCount() { return criticalRiskDocumentCount; }
  public long getWarningDocumentCount() { return warningDocumentCount; }
  public long getManualReviewCount() { return manualReviewCount; }
  public long getApprovedOverrideCount() { return approvedOverrideCount; }
  public long getRejectedDocumentCount() { return rejectedDocumentCount; }
  public long getDisputedCount() { return disputedCount; }
  public long getCompletedOrderCount() { return completedOrderCount; }
  public long getOverduePaymentCount() { return overduePaymentCount; }
  public long getBankAccountChangeCount() { return bankAccountChangeCount; }
  public UUID getLastDocumentTrustRunId() { return lastDocumentTrustRunId; }
  public TrustRiskLevel getLastRiskLevel() { return lastRiskLevel; }
  public Instant getLastTrustSignalAt() { return lastTrustSignalAt; }
  public Instant getLastOrderAt() { return lastOrderAt; }
  public Instant getLastPaymentAt() { return lastPaymentAt; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
