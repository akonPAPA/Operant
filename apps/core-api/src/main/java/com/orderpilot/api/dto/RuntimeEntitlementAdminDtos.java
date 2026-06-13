package com.orderpilot.api.dto;

import com.orderpilot.domain.usage.TenantRuntimePlanCode;
import com.orderpilot.domain.usage.TenantRuntimePlanStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OP-CAP-16I — request/response contracts for the tenant runtime plan + feature entitlement admin
 * command surface. Tenant scope always comes from {@code TenantContext} (X-Tenant-Id); requests never
 * carry a {@code tenantId} so this surface cannot target another tenant. Responses expose only stable
 * governance fields — no pricing, no billing, no internal DB columns.
 *
 * <p>OP-CAP-16J: mutation requests no longer carry a caller-supplied {@code actorId} — the audit
 * actor is resolved from the trusted request context ({@code RequestActorResolver}), never from the
 * body, so it cannot be spoofed. {@link UpdateTenantRuntimePlanRequest} adds {@code
 * clearEffectiveUntil} to explicitly reset the effective-until edge to open-ended (an omitted
 * {@code effectiveUntil} still means "leave unchanged").
 */
public final class RuntimeEntitlementAdminDtos {
  private RuntimeEntitlementAdminDtos() {}

  public record CreateTenantRuntimePlanRequest(
      TenantRuntimePlanCode planCode,
      TenantRuntimePlanStatus status,
      Instant effectiveFrom,
      Instant effectiveUntil) {}

  public record UpdateTenantRuntimePlanRequest(
      TenantRuntimePlanStatus status,
      Instant effectiveFrom,
      Instant effectiveUntil,
      boolean clearEffectiveUntil) {}

  public record UpsertFeatureEntitlementRequest(
      String featureType,
      boolean enabled,
      String reasonCode,
      Instant effectiveFrom,
      Instant effectiveUntil) {}

  public record DisableFeatureEntitlementRequest(String reasonCode) {}

  public record FeatureEntitlementResponse(
      UUID id,
      String featureType,
      boolean enabled,
      String reasonCode,
      Instant effectiveFrom,
      Instant effectiveUntil,
      Instant createdAt,
      Instant updatedAt) {}

  public record TenantRuntimePlanResponse(
      UUID id,
      UUID tenantId,
      TenantRuntimePlanCode planCode,
      TenantRuntimePlanStatus status,
      Instant effectiveFrom,
      Instant effectiveUntil,
      Instant createdAt,
      Instant updatedAt,
      List<FeatureEntitlementResponse> features) {}

  /** Per-feature evaluation as the runtime guard currently sees it (stable reason tokens only). */
  public record FeatureStatusResponse(String featureType, boolean available, String reasonCode) {}

  public record RuntimeEntitlementStatusResponse(
      UUID tenantId,
      String source,
      TenantRuntimePlanResponse currentPlan,
      List<FeatureStatusResponse> featureStatuses) {}
}
