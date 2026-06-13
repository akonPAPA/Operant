package com.orderpilot.domain.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-16E Persistent Tenant Entitlements — a per-feature entitlement under a specific plan, over
 * an effective window.
 *
 * <p>{@code featureType} is stored as the {@code RuntimeFeatureType.name()} token (an application-layer
 * enum) as a plain string, keeping this domain entity free of an application-layer dependency — the
 * same pattern as {@code UsageEvent.workloadType}.
 */
@Entity
@Table(name = "feature_entitlement")
public class FeatureEntitlement {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "plan_id", nullable = false)
  private UUID planId;

  @Column(name = "feature_type", nullable = false, length = 60)
  private String featureType;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Column(name = "reason_code", length = 60)
  private String reasonCode;

  @Column(name = "effective_from", nullable = false)
  private Instant effectiveFrom;

  @Column(name = "effective_until")
  private Instant effectiveUntil;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected FeatureEntitlement() {}

  public FeatureEntitlement(
      UUID tenantId,
      UUID planId,
      String featureType,
      boolean enabled,
      String reasonCode,
      Instant effectiveFrom,
      Instant effectiveUntil,
      Instant now) {
    this.tenantId = tenantId;
    this.planId = planId;
    this.featureType = featureType;
    this.enabled = enabled;
    this.reasonCode = reasonCode;
    this.effectiveFrom = effectiveFrom;
    this.effectiveUntil = effectiveUntil;
    this.createdAt = now;
    this.updatedAt = now;
  }

  /**
   * OP-CAP-16I: controlled in-place update of an existing entitlement row (enabled flag, reason code
   * and effective window). {@code effectiveFrom} is only moved when a non-null value is supplied;
   * {@code effectiveUntil} is set as given (including back to {@code null} for open-ended). Identity,
   * tenant, plan and creation timestamp are immutable.
   */
  public void apply(boolean enabled, String reasonCode, Instant effectiveFrom, Instant effectiveUntil, Instant now) {
    this.enabled = enabled;
    this.reasonCode = reasonCode;
    if (effectiveFrom != null) {
      this.effectiveFrom = effectiveFrom;
    }
    this.effectiveUntil = effectiveUntil;
    this.updatedAt = now;
  }

  /** Effective = {@code now} within {@code [effectiveFrom, effectiveUntil)}. */
  public boolean isEffectiveAt(Instant now) {
    if (effectiveFrom != null && effectiveFrom.isAfter(now)) {
      return false;
    }
    return effectiveUntil == null || effectiveUntil.isAfter(now);
  }

  /** Expired = a bounded window whose end is at or before {@code now}. */
  public boolean isExpiredAt(Instant now) {
    return effectiveUntil != null && !effectiveUntil.isAfter(now);
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public UUID getPlanId() {
    return planId;
  }

  public String getFeatureType() {
    return featureType;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public String getReasonCode() {
    return reasonCode;
  }

  public Instant getEffectiveFrom() {
    return effectiveFrom;
  }

  public Instant getEffectiveUntil() {
    return effectiveUntil;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
