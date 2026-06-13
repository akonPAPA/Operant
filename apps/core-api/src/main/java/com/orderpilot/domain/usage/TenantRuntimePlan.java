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
 * OP-CAP-16E Persistent Tenant Entitlements — a tenant's runtime plan assignment over an effective
 * window. Runtime governance only (no billing/money). A plan grants feature access only while it is
 * {@link TenantRuntimePlanStatus#ACTIVE} and {@code now} falls within {@code [effectiveFrom,
 * effectiveUntil)}.
 */
@Entity
@Table(name = "tenant_runtime_plan")
public class TenantRuntimePlan {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Enumerated(EnumType.STRING)
  @Column(name = "plan_code", nullable = false, length = 40)
  private TenantRuntimePlanCode planCode;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private TenantRuntimePlanStatus status;

  @Column(name = "effective_from", nullable = false)
  private Instant effectiveFrom;

  @Column(name = "effective_until")
  private Instant effectiveUntil;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected TenantRuntimePlan() {}

  public TenantRuntimePlan(
      UUID tenantId,
      TenantRuntimePlanCode planCode,
      TenantRuntimePlanStatus status,
      Instant effectiveFrom,
      Instant effectiveUntil,
      Instant now) {
    this.tenantId = tenantId;
    this.planCode = planCode;
    this.status = status;
    this.effectiveFrom = effectiveFrom;
    this.effectiveUntil = effectiveUntil;
    this.createdAt = now;
    this.updatedAt = now;
  }

  /** Active = ACTIVE status and {@code now} within {@code [effectiveFrom, effectiveUntil)}. */
  public boolean isActiveAt(Instant now) {
    if (status != TenantRuntimePlanStatus.ACTIVE) {
      return false;
    }
    if (effectiveFrom != null && effectiveFrom.isAfter(now)) {
      return false;
    }
    return effectiveUntil == null || effectiveUntil.isAfter(now);
  }

  public UUID getId() {
    return id;
  }

  public UUID getTenantId() {
    return tenantId;
  }

  public TenantRuntimePlanCode getPlanCode() {
    return planCode;
  }

  public TenantRuntimePlanStatus getStatus() {
    return status;
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
