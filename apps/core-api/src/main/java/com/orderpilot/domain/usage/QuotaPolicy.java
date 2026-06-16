package com.orderpilot.domain.usage;

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
 * OP-CAP-16B Usage Metering Foundation — a per-metric usage limit for a tenant (or, in a later
 * stage, a plan). This is a foundation for future quota enforcement, not billing: {@code limitUnits}
 * is a {@code long} count of metric units, never a monetary amount.
 *
 * <p>Stage 16B reads policies advisorily via {@code checkQuota}; nothing here is enforced in a live
 * product request path yet (deferred to Stage 16C).
 */
@Entity
@Table(name = "quota_policy")
public class QuotaPolicy {
  @Id @GeneratedValue private UUID id;

  // Either tenantId or planCode scopes the policy. Stage 16B resolves by tenantId + metricType.
  @Column(name = "tenant_id")
  private UUID tenantId;

  @Column(name = "plan_code", length = 60)
  private String planCode;

  @Enumerated(EnumType.STRING)
  @Column(name = "metric_type", nullable = false, length = 40)
  private UsageMetricType metricType;

  @Enumerated(EnumType.STRING)
  @Column(name = "period_type", nullable = false, length = 20)
  private UsagePeriodType periodType;

  @Column(name = "limit_units", nullable = false)
  private long limitUnits;

  @Enumerated(EnumType.STRING)
  @Column(name = "enforcement_mode", nullable = false, length = 20)
  private QuotaEnforcementMode enforcementMode;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected QuotaPolicy() {}

  public QuotaPolicy(
      UUID tenantId,
      String planCode,
      UsageMetricType metricType,
      UsagePeriodType periodType,
      long limitUnits,
      QuotaEnforcementMode enforcementMode,
      Instant now) {
    this.tenantId = tenantId;
    this.planCode = planCode;
    this.metricType = metricType;
    this.periodType = periodType;
    this.limitUnits = Math.max(0L, limitUnits);
    this.enforcementMode = enforcementMode;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public String getPlanCode() {
    return planCode;
  }

  public UsageMetricType getMetricType() {
    return metricType;
  }

  public UsagePeriodType getPeriodType() {
    return periodType;
  }

  public long getLimitUnits() {
    return limitUnits;
  }

  public QuotaEnforcementMode getEnforcementMode() {
    return enforcementMode;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
