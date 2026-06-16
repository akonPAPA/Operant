package com.orderpilot.domain.trust.analytics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Derived, rebuildable, tenant-scoped distribution of OP-CAP-17D risk decisions for one period (daily
 * {@code periodKey} {@code yyyy-MM-dd}). The decisions remain the system of record; this single row per
 * (tenant, period) exists for fast dashboard metrics without re-aggregating the decision table on every
 * request. {@code avgRiskScore} is bounded NUMERIC(6,2). Unique per (tenant, period).
 */
@Entity
@Table(name = "trust_risk_distribution_view",
    uniqueConstraints = @UniqueConstraint(name = "ux_trust_risk_distribution_view",
        columnNames = {"tenant_id", "period_key"}))
public class TrustRiskDistributionView {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Column(name = "period_key", nullable = false, length = 32) private String periodKey;

  @Column(name = "period_start", nullable = false) private Instant periodStart;

  @Column(name = "period_end", nullable = false) private Instant periodEnd;

  @Column(name = "low_count", nullable = false) private long lowCount;

  @Column(name = "medium_count", nullable = false) private long mediumCount;

  @Column(name = "high_count", nullable = false) private long highCount;

  @Column(name = "critical_count", nullable = false) private long criticalCount;

  @Column(name = "approval_required_count", nullable = false) private long approvalRequiredCount;

  @Column(name = "blocking_count", nullable = false) private long blockingCount;

  @Column(name = "override_count", nullable = false) private long overrideCount;

  @Column(name = "avg_risk_score", nullable = false, precision = 6, scale = 2) private BigDecimal avgRiskScore;

  @Column(name = "last_projected_at", nullable = false) private Instant lastProjectedAt;

  protected TrustRiskDistributionView() {}

  public TrustRiskDistributionView(UUID tenantId, String periodKey, Instant periodStart, Instant periodEnd) {
    this.tenantId = tenantId;
    this.periodKey = periodKey;
    this.periodStart = periodStart;
    this.periodEnd = periodEnd;
  }

  /** Idempotent in-place refresh of all projected metrics. */
  public void apply(Instant periodStart, Instant periodEnd, long lowCount, long mediumCount, long highCount,
      long criticalCount, long approvalRequiredCount, long blockingCount, long overrideCount,
      BigDecimal avgRiskScore, Instant projectedAt) {
    this.periodStart = periodStart;
    this.periodEnd = periodEnd;
    this.lowCount = lowCount;
    this.mediumCount = mediumCount;
    this.highCount = highCount;
    this.criticalCount = criticalCount;
    this.approvalRequiredCount = approvalRequiredCount;
    this.blockingCount = blockingCount;
    this.overrideCount = overrideCount;
    this.avgRiskScore = avgRiskScore;
    this.lastProjectedAt = projectedAt;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public String getPeriodKey() { return periodKey; }
  public Instant getPeriodStart() { return periodStart; }
  public Instant getPeriodEnd() { return periodEnd; }
  public long getLowCount() { return lowCount; }
  public long getMediumCount() { return mediumCount; }
  public long getHighCount() { return highCount; }
  public long getCriticalCount() { return criticalCount; }
  public long getApprovalRequiredCount() { return approvalRequiredCount; }
  public long getBlockingCount() { return blockingCount; }
  public long getOverrideCount() { return overrideCount; }
  public BigDecimal getAvgRiskScore() { return avgRiskScore; }
  public Instant getLastProjectedAt() { return lastProjectedAt; }
}
