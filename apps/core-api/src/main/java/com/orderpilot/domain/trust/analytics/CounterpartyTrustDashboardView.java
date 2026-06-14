package com.orderpilot.domain.trust.analytics;

import com.orderpilot.domain.trust.TrustTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-17E Trust Analytics Read Models.
 *
 * Derived, rebuildable, tenant-scoped summary for the counterparty trust page. Combines the OP-CAP-17B
 * counterparty trust profile, OP-CAP-17C payment obligation aggregates, and OP-CAP-17D high/critical
 * decision counts into one fast row. The operational records remain the system of record. Business
 * counters use {@code long}; amounts use {@link BigDecimal} and are populated only for a single-currency
 * counterparty (otherwise {@code primaryCurrency} is {@code "MIXED"} and {@code outstandingAmount} is
 * null — no FX conversion is ever performed). Unique per (tenant, counterparty).
 */
@Entity
@Table(name = "counterparty_trust_dashboard_view",
    uniqueConstraints = @UniqueConstraint(name = "ux_counterparty_trust_dashboard_view",
        columnNames = {"tenant_id", "counterparty_id"}))
public class CounterpartyTrustDashboardView {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Column(name = "counterparty_id", nullable = false) private UUID counterpartyId;

  @Column(name = "trust_score", nullable = false) private int trustScore;

  @Enumerated(EnumType.STRING)
  @Column(name = "trust_tier", nullable = false, length = 16) private TrustTier trustTier;

  @Column(name = "order_count", nullable = false) private long orderCount;

  @Column(name = "completed_order_count", nullable = false) private long completedOrderCount;

  @Column(name = "paid_on_time_count", nullable = false) private long paidOnTimeCount;

  @Column(name = "overdue_count", nullable = false) private long overdueCount;

  @Column(name = "disputed_count", nullable = false) private long disputedCount;

  @Column(name = "high_risk_document_count", nullable = false) private long highRiskDocumentCount;

  @Column(name = "critical_risk_document_count", nullable = false) private long criticalRiskDocumentCount;

  @Column(name = "high_risk_decision_count", nullable = false) private long highRiskDecisionCount;

  @Column(name = "critical_risk_decision_count", nullable = false) private long criticalRiskDecisionCount;

  @Column(name = "open_payment_obligation_count", nullable = false) private long openPaymentObligationCount;

  @Column(name = "overdue_payment_obligation_count", nullable = false) private long overduePaymentObligationCount;

  @Column(name = "outstanding_amount", precision = 19, scale = 4) private BigDecimal outstandingAmount;

  @Column(name = "primary_currency", length = 8) private String primaryCurrency;

  @Column(name = "last_order_at") private Instant lastOrderAt;

  @Column(name = "last_payment_at") private Instant lastPaymentAt;

  @Column(name = "last_high_risk_at") private Instant lastHighRiskAt;

  @Column(name = "last_critical_risk_at") private Instant lastCriticalRiskAt;

  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  @Column(name = "last_projected_at", nullable = false) private Instant lastProjectedAt;

  protected CounterpartyTrustDashboardView() {}

  public CounterpartyTrustDashboardView(UUID tenantId, UUID counterpartyId) {
    this.tenantId = tenantId;
    this.counterpartyId = counterpartyId;
  }

  /** Idempotent in-place refresh of all projected fields. */
  public void apply(int trustScore, TrustTier trustTier, long orderCount, long completedOrderCount,
      long paidOnTimeCount, long overdueCount, long disputedCount, long highRiskDocumentCount,
      long criticalRiskDocumentCount, long highRiskDecisionCount, long criticalRiskDecisionCount,
      long openPaymentObligationCount, long overduePaymentObligationCount, BigDecimal outstandingAmount,
      String primaryCurrency, Instant lastOrderAt, Instant lastPaymentAt, Instant lastHighRiskAt,
      Instant lastCriticalRiskAt, Instant projectedAt) {
    this.trustScore = trustScore;
    this.trustTier = trustTier;
    this.orderCount = orderCount;
    this.completedOrderCount = completedOrderCount;
    this.paidOnTimeCount = paidOnTimeCount;
    this.overdueCount = overdueCount;
    this.disputedCount = disputedCount;
    this.highRiskDocumentCount = highRiskDocumentCount;
    this.criticalRiskDocumentCount = criticalRiskDocumentCount;
    this.highRiskDecisionCount = highRiskDecisionCount;
    this.criticalRiskDecisionCount = criticalRiskDecisionCount;
    this.openPaymentObligationCount = openPaymentObligationCount;
    this.overduePaymentObligationCount = overduePaymentObligationCount;
    this.outstandingAmount = outstandingAmount;
    this.primaryCurrency = primaryCurrency;
    this.lastOrderAt = lastOrderAt;
    this.lastPaymentAt = lastPaymentAt;
    this.lastHighRiskAt = lastHighRiskAt;
    this.lastCriticalRiskAt = lastCriticalRiskAt;
    this.updatedAt = projectedAt;
    this.lastProjectedAt = projectedAt;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getCounterpartyId() { return counterpartyId; }
  public int getTrustScore() { return trustScore; }
  public TrustTier getTrustTier() { return trustTier; }
  public long getOrderCount() { return orderCount; }
  public long getCompletedOrderCount() { return completedOrderCount; }
  public long getPaidOnTimeCount() { return paidOnTimeCount; }
  public long getOverdueCount() { return overdueCount; }
  public long getDisputedCount() { return disputedCount; }
  public long getHighRiskDocumentCount() { return highRiskDocumentCount; }
  public long getCriticalRiskDocumentCount() { return criticalRiskDocumentCount; }
  public long getHighRiskDecisionCount() { return highRiskDecisionCount; }
  public long getCriticalRiskDecisionCount() { return criticalRiskDecisionCount; }
  public long getOpenPaymentObligationCount() { return openPaymentObligationCount; }
  public long getOverduePaymentObligationCount() { return overduePaymentObligationCount; }
  public BigDecimal getOutstandingAmount() { return outstandingAmount; }
  public String getPrimaryCurrency() { return primaryCurrency; }
  public Instant getLastOrderAt() { return lastOrderAt; }
  public Instant getLastPaymentAt() { return lastPaymentAt; }
  public Instant getLastHighRiskAt() { return lastHighRiskAt; }
  public Instant getLastCriticalRiskAt() { return lastCriticalRiskAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public Instant getLastProjectedAt() { return lastProjectedAt; }
}
