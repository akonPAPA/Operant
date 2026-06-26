package com.orderpilot.api.rest;

import com.orderpilot.api.dto.IncidentInternalDtos.BreakGlassDecisionRequest;
import com.orderpilot.api.dto.IncidentInternalDtos.BreakGlassResponse;
import com.orderpilot.api.dto.IncidentInternalDtos.CloseIncidentRequest;
import com.orderpilot.api.dto.IncidentInternalDtos.CreateBreakGlassRequest;
import com.orderpilot.api.dto.IncidentInternalDtos.CreateIncidentRequest;
import com.orderpilot.api.dto.IncidentInternalDtos.IncidentResponse;
import com.orderpilot.application.services.incident.IncidentResponseService;
import com.orderpilot.application.services.support.SupportAccessDeniedException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.incident.BreakGlassAccessRequest;
import com.orderpilot.domain.incident.IncidentRecord;
import com.orderpilot.security.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-53 — internal owner-company incident-response / break-glass command/query surface, mounted under
 * {@code /api/v1/internal/support/**} (the same internal support route family as OP-CAP-51/52). Every route
 * is protected by a dedicated staff {@code STAFF_*} {@link com.orderpilot.security.ApiPermission} enforced at
 * the route edge by {@code ApiPermissionInterceptor}; a tenant customer/operator permission header can never
 * reach these routes, and each verb requires a distinct STAFF_* permission.
 *
 * <p>The target tenant is the trusted {@code X-Tenant-Id} context and the acting staff actor is resolved from
 * the trusted actor header — never from a request body. Where a {@code {tenantId}} path variable is present it
 * must equal the trusted tenant context; a mismatch fails closed (cross-tenant denial). No endpoint here
 * mutates business truth, runs SQL/script, or calls a connector/ERP.
 */
@RestController
public class InternalIncidentController {
  private static final String BASE = "/api/v1/internal/support";

  private final IncidentResponseService incidentService;
  private final RequestActorResolver actorResolver;

  public InternalIncidentController(
      IncidentResponseService incidentService, RequestActorResolver actorResolver) {
    this.incidentService = incidentService;
    this.actorResolver = actorResolver;
  }

  // --- incidents (STAFF_INCIDENT_CREATE / STAFF_INCIDENT_READ / STAFF_INCIDENT_CLOSE) ---

  @PostMapping(BASE + "/incidents")
  public IncidentResponse createIncident(
      @RequestBody CreateIncidentRequest request, HttpServletRequest http) {
    UUID tenantId = TenantContext.requireTenantId();
    UUID actor = actorResolver.resolveVerifiedActor(http, tenantId);
    return toIncidentResponse(incidentService.createIncident(
        tenantId, actor, request.title(), request.reason(), request.severity(), request.incidentType()));
  }

  @PostMapping(BASE + "/incidents/{incidentId}/close")
  public IncidentResponse closeIncident(
      @PathVariable UUID incidentId,
      @RequestBody(required = false) CloseIncidentRequest request,
      HttpServletRequest http) {
    UUID tenantId = TenantContext.requireTenantId();
    UUID actor = actorResolver.resolveVerifiedActor(http, tenantId);
    return toIncidentResponse(incidentService.closeIncident(
        tenantId, actor, incidentId, request == null ? null : request.closureReason()));
  }

  @GetMapping(BASE + "/incidents/{incidentId}")
  public IncidentResponse getIncident(@PathVariable UUID incidentId) {
    UUID tenantId = TenantContext.requireTenantId();
    return toIncidentResponse(incidentService.getIncident(tenantId, incidentId));
  }

  // --- break-glass access requests ---

  @PostMapping(BASE + "/tenants/{tenantId}/incidents/{incidentId}/break-glass-requests")
  public BreakGlassResponse requestBreakGlass(
      @PathVariable UUID tenantId,
      @PathVariable UUID incidentId,
      @RequestBody CreateBreakGlassRequest request,
      HttpServletRequest http) {
    UUID contextTenant = requireMatchingTenant(tenantId);
    UUID actor = actorResolver.resolveVerifiedActor(http, contextTenant);
    return toBreakGlassResponse(incidentService.requestBreakGlass(
        contextTenant, actor, incidentId, request.scope(), request.reason(), ttl(request.ttlSeconds())));
  }

  @PostMapping(BASE + "/tenants/{tenantId}/break-glass-requests/{requestId}/approve")
  public BreakGlassResponse approveBreakGlass(
      @PathVariable UUID tenantId,
      @PathVariable UUID requestId,
      @RequestBody(required = false) BreakGlassDecisionRequest request,
      HttpServletRequest http) {
    UUID contextTenant = requireMatchingTenant(tenantId);
    UUID actor = actorResolver.resolveVerifiedActor(http, contextTenant);
    return toBreakGlassResponse(incidentService.approveBreakGlass(contextTenant, actor, requestId, note(request)));
  }

  @PostMapping(BASE + "/tenants/{tenantId}/break-glass-requests/{requestId}/reject")
  public BreakGlassResponse rejectBreakGlass(
      @PathVariable UUID tenantId,
      @PathVariable UUID requestId,
      @RequestBody(required = false) BreakGlassDecisionRequest request,
      HttpServletRequest http) {
    UUID contextTenant = requireMatchingTenant(tenantId);
    UUID actor = actorResolver.resolveVerifiedActor(http, contextTenant);
    return toBreakGlassResponse(incidentService.rejectBreakGlass(contextTenant, actor, requestId, note(request)));
  }

  @PostMapping(BASE + "/tenants/{tenantId}/break-glass-requests/{requestId}/revoke")
  public BreakGlassResponse revokeBreakGlass(
      @PathVariable UUID tenantId,
      @PathVariable UUID requestId,
      @RequestBody(required = false) BreakGlassDecisionRequest request,
      HttpServletRequest http) {
    UUID contextTenant = requireMatchingTenant(tenantId);
    UUID actor = actorResolver.resolveVerifiedActor(http, contextTenant);
    return toBreakGlassResponse(incidentService.revokeBreakGlass(contextTenant, actor, requestId, note(request)));
  }

  private static String note(BreakGlassDecisionRequest request) {
    return request == null ? null : request.note();
  }

  private UUID requireMatchingTenant(UUID pathTenantId) {
    UUID contextTenant = TenantContext.requireTenantId();
    if (pathTenantId == null || !pathTenantId.equals(contextTenant)) {
      // Path scope must equal the trusted tenant context. A mismatch is a cross-tenant attempt — fail closed.
      throw new SupportAccessDeniedException("Break-glass access denied");
    }
    return contextTenant;
  }

  private static Duration ttl(Long ttlSeconds) {
    if (ttlSeconds == null || ttlSeconds <= 0) {
      throw new IllegalArgumentException("ttlSeconds must be positive");
    }
    return Duration.ofSeconds(ttlSeconds);
  }

  private IncidentResponse toIncidentResponse(IncidentRecord incident) {
    return new IncidentResponse(
        incident.getId(),
        incident.getTenantId(),
        incident.getTitle(),
        incident.getReason(),
        incident.getSeverity().name(),
        incident.getIncidentType().name(),
        incident.getStatus().name(),
        incident.getCreatedAt(),
        incident.getUpdatedAt(),
        incident.getClosedAt(),
        incident.getClosureReason());
  }

  private BreakGlassResponse toBreakGlassResponse(BreakGlassAccessRequest request) {
    return new BreakGlassResponse(
        request.getId(),
        request.getTenantId(),
        request.getIncidentId(),
        request.getScope().name(),
        request.getStatus().name(),
        request.getReason(),
        request.getRequestedAt(),
        request.getDecidedAt(),
        request.getExpiresAt(),
        request.getRevokedAt());
  }
}
