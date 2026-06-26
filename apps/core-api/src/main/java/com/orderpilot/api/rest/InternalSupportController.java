package com.orderpilot.api.rest;

import com.orderpilot.api.dto.SupportInternalDtos.CreateSupportAccessGrantRequest;
import com.orderpilot.api.dto.SupportInternalDtos.DataRepairDryRunRequest;
import com.orderpilot.api.dto.SupportInternalDtos.DataRepairDryRunResponse;
import com.orderpilot.api.dto.SupportInternalDtos.MaintenanceActionRecordRequest;
import com.orderpilot.api.dto.SupportInternalDtos.MaintenanceActionRecordResponse;
import com.orderpilot.api.dto.SupportInternalDtos.SupportAccessGrantResponse;
import com.orderpilot.api.dto.SupportInternalDtos.SupportTenantDiagnosticsResponse;
import com.orderpilot.application.services.support.DataRepairService;
import com.orderpilot.application.services.support.MaintenanceActionService;
import com.orderpilot.application.services.support.SupportAccessDeniedException;
import com.orderpilot.application.services.support.SupportAccessService;
import com.orderpilot.application.services.support.SupportDiagnosticsService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.support.StaffSupportScope;
import com.orderpilot.domain.support.SupportAccessGrant;
import com.orderpilot.security.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-51 — internal owner-company support/maintenance command/query surface, mounted under
 * {@code /api/v1/internal/support/**}. Every route here is protected by a dedicated staff/support
 * {@code ApiPermission} (route-edge gate enforced by {@code ApiPermissionInterceptor}); a tenant
 * customer/operator permission header can never reach these routes. The diagnostics / maintenance /
 * data-repair actions additionally require a scoped, reasoned, unexpired {@link SupportAccessGrant}
 * validated by {@link SupportAccessService} (second layer).
 *
 * <p>The target tenant is the trusted {@code X-Tenant-Id} context, and the acting staff actor is resolved
 * from the trusted actor header — never from a request body. Where a {@code {tenantId}} path variable is
 * present it must equal the trusted tenant context; a mismatch fails closed (cross-tenant denial).
 */
@RestController
public class InternalSupportController {
  private static final String BASE = "/api/v1/internal/support";

  private final SupportAccessService supportAccessService;
  private final SupportDiagnosticsService diagnosticsService;
  private final MaintenanceActionService maintenanceActionService;
  private final DataRepairService dataRepairService;
  private final RequestActorResolver actorResolver;

  public InternalSupportController(
      SupportAccessService supportAccessService,
      SupportDiagnosticsService diagnosticsService,
      MaintenanceActionService maintenanceActionService,
      DataRepairService dataRepairService,
      RequestActorResolver actorResolver) {
    this.supportAccessService = supportAccessService;
    this.diagnosticsService = diagnosticsService;
    this.maintenanceActionService = maintenanceActionService;
    this.dataRepairService = dataRepairService;
    this.actorResolver = actorResolver;
  }

  // --- support access grants (STAFF_SUPPORT_GRANT_MANAGE / STAFF_SUPPORT_READ) ---

  @PostMapping(BASE + "/access-grants")
  public SupportAccessGrantResponse createGrant(
      @RequestBody CreateSupportAccessGrantRequest request, HttpServletRequest http) {
    UUID tenantId = TenantContext.requireTenantId();
    UUID actor = actorResolver.resolveVerifiedActor(http, tenantId);
    SupportAccessGrant grant = supportAccessService.createGrant(
        request.granteeStaffUserId(),
        tenantId,
        parseScope(request.scope()),
        request.supportCaseRef(),
        ttl(request.ttlSeconds()),
        actor);
    return toGrantResponse(grant);
  }

  @PostMapping(BASE + "/access-grants/{grantId}/revoke")
  public SupportAccessGrantResponse revokeGrant(@PathVariable UUID grantId, HttpServletRequest http) {
    UUID tenantId = TenantContext.requireTenantId();
    UUID actor = actorResolver.resolveVerifiedActor(http, tenantId);
    return toGrantResponse(supportAccessService.revokeGrant(grantId, tenantId, actor));
  }

  @GetMapping(BASE + "/tenants/{tenantId}/access-grants")
  public List<SupportAccessGrantResponse> listGrants(@PathVariable UUID tenantId) {
    UUID contextTenant = requireMatchingTenant(tenantId);
    return supportAccessService.listGrants(contextTenant).stream().map(this::toGrantResponse).toList();
  }

  // --- safe read-only tenant diagnostics (STAFF_SUPPORT_READ + DIAGNOSTICS grant) ---

  @GetMapping(BASE + "/tenants/{tenantId}/diagnostics")
  public SupportTenantDiagnosticsResponse diagnostics(@PathVariable UUID tenantId, HttpServletRequest http) {
    UUID contextTenant = requireMatchingTenant(tenantId);
    UUID actor = actorResolver.resolveVerifiedActor(http, contextTenant);
    supportAccessService.authorize(actor, contextTenant, StaffSupportScope.DIAGNOSTICS);
    return diagnosticsService.diagnose(contextTenant);
  }

  // --- maintenance/update audit record (STAFF_MAINTENANCE_RECORD + MAINTENANCE grant) ---

  @PostMapping(BASE + "/tenants/{tenantId}/maintenance-records")
  public MaintenanceActionRecordResponse recordMaintenance(
      @PathVariable UUID tenantId,
      @RequestBody MaintenanceActionRecordRequest request,
      HttpServletRequest http) {
    UUID contextTenant = requireMatchingTenant(tenantId);
    UUID actor = actorResolver.resolveVerifiedActor(http, contextTenant);
    supportAccessService.authorize(actor, contextTenant, StaffSupportScope.MAINTENANCE);
    return maintenanceActionService.record(
        contextTenant, actor, request.actionType(), request.reason(), request.targetScope());
  }

  // --- controlled data-repair dry-run (STAFF_DATA_REPAIR_DRYRUN + DATA_REPAIR grant) ---

  @PostMapping(BASE + "/tenants/{tenantId}/data-repair-requests/dry-run")
  public DataRepairDryRunResponse dataRepairDryRun(
      @PathVariable UUID tenantId,
      @RequestBody DataRepairDryRunRequest request,
      HttpServletRequest http) {
    UUID contextTenant = requireMatchingTenant(tenantId);
    UUID actor = actorResolver.resolveVerifiedActor(http, contextTenant);
    supportAccessService.authorize(actor, contextTenant, StaffSupportScope.DATA_REPAIR);
    return dataRepairService.requestDryRun(contextTenant, actor, request.targetType(), request.reason());
  }

  private UUID requireMatchingTenant(UUID pathTenantId) {
    UUID contextTenant = TenantContext.requireTenantId();
    if (pathTenantId == null || !pathTenantId.equals(contextTenant)) {
      // Path scope must equal the trusted tenant context. A mismatch is a cross-tenant attempt — fail closed.
      throw new SupportAccessDeniedException("Support access denied");
    }
    return contextTenant;
  }

  private static StaffSupportScope parseScope(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("scope is required");
    }
    try {
      return StaffSupportScope.valueOf(raw.trim());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unknown scope: " + raw);
    }
  }

  private static Duration ttl(Long ttlSeconds) {
    if (ttlSeconds == null || ttlSeconds <= 0) {
      throw new IllegalArgumentException("ttlSeconds must be positive");
    }
    return Duration.ofSeconds(ttlSeconds);
  }

  private SupportAccessGrantResponse toGrantResponse(SupportAccessGrant grant) {
    return new SupportAccessGrantResponse(
        grant.getId(),
        grant.getTenantId(),
        grant.getScope().name(),
        grant.getStatus().name(),
        grant.getSupportCaseRef(),
        grant.getExpiresAt(),
        grant.getCreatedAt());
  }
}
