package com.orderpilot.domain.usage;

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
 * OP-CAP-16B Usage Metering Foundation — an aggregated counter for fast quota checks, unique per
 * {@code (tenantId, metricType, periodKey)}.
 *
 * <p>{@code unitsUsed} is a {@code long}; increments go through bounded, overflow-safe arithmetic
 * (saturating at {@link Long#MAX_VALUE}) so accumulated or large external inputs can never wrap.
 */
@Entity
@Table(
    name = "usage_counter",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_usage_counter_scope",
            columnNames = {"tenant_id", "metric_type", "period_key"}))
public class UsageCounter {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Enumerated(EnumType.STRING)
  @Column(name = "metric_type", nullable = false, length = 40)
  private UsageMetricType metricType;

  @Column(name = "period_key", nullable = false, length = 32)
  private String periodKey;

  @Column(name = "units_used", nullable = false)
  private long unitsUsed;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected UsageCounter() {}

  public UsageCounter(
      UUID tenantId, UsageMetricType metricType, String periodKey, long initialUnits, Instant now) {
    this.tenantId = tenantId;
    this.metricType = metricType;
    this.periodKey = periodKey;
    this.unitsUsed = Math.max(0L, initialUnits);
    this.createdAt = now;
    this.updatedAt = now;
  }

  /**
   * Saturating add of {@code additionalUnits} (clamped non-negative) to the running total. Returns
   * the new total. Never overflows: the sum is capped at {@link Long#MAX_VALUE}.
   */
  public long addUnits(long additionalUnits, Instant now) {
    long safeAddition = Math.max(0L, additionalUnits);
    this.unitsUsed = UsageMath.safeAdd(this.unitsUsed, safeAddition);
    this.updatedAt = now;
    return this.unitsUsed;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UsageMetricType getMetricType() {
    return metricType;
  }

  public String getPeriodKey() {
    return periodKey;
  }

  public long getUnitsUsed() {
    return unitsUsed;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
