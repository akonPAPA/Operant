package com.orderpilot.domain.trust.analytics;

import com.orderpilot.domain.trust.TrustRiskLevel;
import com.orderpilot.domain.trust.TrustSignalCode;
import com.orderpilot.domain.trust.TrustSignalSeverity;
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
 * Derived, rebuildable, tenant-scoped aggregation of OP-CAP-17A document trust signals grouped by period
 * (daily {@code periodKey} {@code yyyy-MM-dd}), signal code, and severity. The signals remain the system
 * of record; this row exists for fast anomaly-trend reads. {@code counterpartyId} is nullable and is null
 * in this stage (17A signals are keyed by trust run, not counterparty — documented limitation).
 * {@code riskLevel} is nullable (signals carry severity, not a risk level). Unique per
 * (tenant, period, signal code, severity, counterparty).
 */
@Entity
@Table(name = "document_anomaly_trend_view",
    uniqueConstraints = @UniqueConstraint(name = "ux_document_anomaly_trend_view",
        columnNames = {"tenant_id", "period_key", "signal_code", "severity", "counterparty_id"}))
public class DocumentAnomalyTrendView {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Column(name = "period_key", nullable = false, length = 32) private String periodKey;

  @Column(name = "period_start", nullable = false) private Instant periodStart;

  @Column(name = "period_end", nullable = false) private Instant periodEnd;

  @Enumerated(EnumType.STRING)
  @Column(name = "signal_code", nullable = false, length = 48) private TrustSignalCode signalCode;

  @Enumerated(EnumType.STRING)
  @Column(name = "severity", nullable = false, length = 16) private TrustSignalSeverity severity;

  @Enumerated(EnumType.STRING)
  @Column(name = "risk_level", length = 16) private TrustRiskLevel riskLevel;

  @Column(name = "counterparty_id") private UUID counterpartyId;

  @Column(name = "occurrence_count", nullable = false) private long count;

  @Column(name = "high_count", nullable = false) private long highCount;

  @Column(name = "critical_count", nullable = false) private long criticalCount;

  @Column(name = "latest_seen_at", nullable = false) private Instant latestSeenAt;

  @Column(name = "last_projected_at", nullable = false) private Instant lastProjectedAt;

  protected DocumentAnomalyTrendView() {}

  public DocumentAnomalyTrendView(UUID tenantId, String periodKey, Instant periodStart, Instant periodEnd,
      TrustSignalCode signalCode, TrustSignalSeverity severity, TrustRiskLevel riskLevel,
      UUID counterpartyId, long count, long highCount, long criticalCount, Instant latestSeenAt,
      Instant projectedAt) {
    this.tenantId = tenantId;
    this.periodKey = periodKey;
    this.periodStart = periodStart;
    this.periodEnd = periodEnd;
    this.signalCode = signalCode;
    this.severity = severity;
    this.riskLevel = riskLevel;
    this.counterpartyId = counterpartyId;
    this.count = count;
    this.highCount = highCount;
    this.criticalCount = criticalCount;
    this.latestSeenAt = latestSeenAt;
    this.lastProjectedAt = projectedAt;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public String getPeriodKey() { return periodKey; }
  public Instant getPeriodStart() { return periodStart; }
  public Instant getPeriodEnd() { return periodEnd; }
  public TrustSignalCode getSignalCode() { return signalCode; }
  public TrustSignalSeverity getSeverity() { return severity; }
  public TrustRiskLevel getRiskLevel() { return riskLevel; }
  public UUID getCounterpartyId() { return counterpartyId; }
  public long getCount() { return count; }
  public long getHighCount() { return highCount; }
  public long getCriticalCount() { return criticalCount; }
  public Instant getLatestSeenAt() { return latestSeenAt; }
  public Instant getLastProjectedAt() { return lastProjectedAt; }
}
