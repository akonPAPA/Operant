package com.orderpilot.api.rest;

import com.orderpilot.api.dto.RuntimeEntitlementAdminDtos.CreateTenantRuntimePlanRequest;
import com.orderpilot.api.dto.RuntimeEntitlementAdminDtos.DisableFeatureEntitlementRequest;
import com.orderpilot.api.dto.RuntimeEntitlementAdminDtos.FeatureEntitlementResponse;
import com.orderpilot.api.dto.RuntimeEntitlementAdminDtos.RuntimeEntitlementStatusResponse;
import com.orderpilot.api.dto.RuntimeEntitlementAdminDtos.TenantRuntimePlanResponse;
import com.orderpilot.api.dto.RuntimeEntitlementAdminDtos.UpdateTenantRuntimePlanRequest;
import com.orderpilot.api.dto.RuntimeEntitlementAdminDtos.UpsertFeatureEntitlementRequest;
import com.orderpilot.application.services.runtime.RuntimeEntitlementAdminService;
import com.orderpilot.application.services.runtime.RuntimeEntitlementAdminService.CreatePlanCommand;
import com.orderpilot.application.services.runtime.RuntimeEntitlementAdminService.DisableFeatureCommand;
import com.orderpilot.application.services.runtime.RuntimeEntitlementAdminService.UpdatePlanCommand;
import com.orderpilot.application.services.runtime.RuntimeEntitlementAdminService.UpsertFeatureCommand;
import com.orderpilot.application.services.runtime.RuntimeFeatureType;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.security.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-16I/16J — internal/admin runtime governance command/query surface. Reads require
 * {@code RUNTIME_ENTITLEMENT_READ}; all mutations require {@code RUNTIME_ENTITLEMENT_MANAGE}
 * (enforced by {@code ApiPermissionInterceptor} on the {@code /api/v1/runtime} prefix). Tenant scope
 * comes from {@code TenantContext}; the controller holds no business logic — it maps requests to
 * service commands. This is runtime governance only (no billing/pricing/payment).
 *
 * <p>OP-CAP-16J: the audit actor is resolved from the trusted request context
 * ({@link RequestActorResolver}), not from the request body — a request cannot spoof the audit actor.
 */
@RestController
public class RuntimeEntitlementAdminController {
  private final RuntimeEntitlementAdminService service;
  private final RequestActorResolver actorResolver;

  public RuntimeEntitlementAdminController(RuntimeEntitlementAdminService service, RequestActorResolver actorResolver) {
    this.service = service;
    this.actorResolver = actorResolver;
  }

  @GetMapping("/api/v1/runtime/entitlements")
  public RuntimeEntitlementStatusResponse currentEntitlements() {
    return service.getCurrentRuntimeEntitlements();
  }

  @PostMapping("/api/v1/runtime/plans")
  public TenantRuntimePlanResponse createPlan(@RequestBody CreateTenantRuntimePlanRequest request, HttpServletRequest http) {
    UUID actor = actorResolver.resolveVerifiedActor(http, TenantContext.getTenantId().orElse(null));
    return service.createPlan(
        new CreatePlanCommand(request.planCode(), request.status(), request.effectiveFrom(), request.effectiveUntil(), actor));
  }

  @PatchMapping("/api/v1/runtime/plans/{planId}")
  public TenantRuntimePlanResponse updatePlan(
      @PathVariable UUID planId, @RequestBody UpdateTenantRuntimePlanRequest request, HttpServletRequest http) {
    UUID actor = actorResolver.resolveVerifiedActor(http, TenantContext.getTenantId().orElse(null));
    return service.updatePlan(
        new UpdatePlanCommand(planId, request.status(), request.effectiveFrom(), request.effectiveUntil(),
            request.clearEffectiveUntil(), actor));
  }

  @PostMapping("/api/v1/runtime/plans/{planId}/features")
  public FeatureEntitlementResponse upsertFeature(
      @PathVariable UUID planId, @RequestBody UpsertFeatureEntitlementRequest request, HttpServletRequest http) {
    UUID actor = actorResolver.resolveVerifiedActor(http, TenantContext.getTenantId().orElse(null));
    return service.upsertFeatureEntitlement(
        new UpsertFeatureCommand(planId, parseFeature(request.featureType()), request.enabled(), request.reasonCode(),
            request.effectiveFrom(), request.effectiveUntil(), actor));
  }

  @PatchMapping("/api/v1/runtime/plans/{planId}/features/{featureType}")
  public FeatureEntitlementResponse updateFeature(
      @PathVariable UUID planId, @PathVariable String featureType, @RequestBody UpsertFeatureEntitlementRequest request,
      HttpServletRequest http) {
    UUID actor = actorResolver.resolveVerifiedActor(http, TenantContext.getTenantId().orElse(null));
    return service.upsertFeatureEntitlement(
        new UpsertFeatureCommand(planId, parseFeature(featureType), request.enabled(), request.reasonCode(),
            request.effectiveFrom(), request.effectiveUntil(), actor));
  }

  @DeleteMapping("/api/v1/runtime/plans/{planId}/features/{featureType}")
  public FeatureEntitlementResponse disableFeature(
      @PathVariable UUID planId, @PathVariable String featureType,
      @RequestBody(required = false) DisableFeatureEntitlementRequest request, HttpServletRequest http) {
    UUID actor = actorResolver.resolveVerifiedActor(http, TenantContext.getTenantId().orElse(null));
    return service.disableFeatureEntitlement(
        new DisableFeatureCommand(planId, parseFeature(featureType), request == null ? null : request.reasonCode(), actor));
  }

  private static RuntimeFeatureType parseFeature(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("featureType is required");
    }
    try {
      return RuntimeFeatureType.valueOf(value.trim());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unknown featureType: " + value);
    }
  }
}
