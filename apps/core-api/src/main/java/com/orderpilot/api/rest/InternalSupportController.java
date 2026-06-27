package com.orderpilot.api.rest;

import com.orderpilot.api.dto.SupportInternalDtos.CreateSupportAccessGrantRequest;
import com.orderpilot.api.dto.SupportInternalDtos.DataRepairApprovalDecisionRequest;
import com.orderpilot.api.dto.SupportInternalDtos.DataRepairApprovalRequest;
import com.orderpilot.api.dto.SupportInternalDtos.DataRepairDryRunRequest;
import com.orderpilot.api.dto.SupportInternalDtos.DataRepairDryRunResponse;
import com.orderpilot.api.dto.SupportInternalDtos.DataRepairRequestResponse;
import com.orderpilot.api.dto.SupportInternalDtos.MaintenanceActionRecordRequest;
import com.orderpilot.api.dto.SupportInternalDtos.MaintenanceActionRecordResponse;
import com.orderpilot.api.dto.SupportInternalDtos.ProcessingJobRepairExecuteRequest;
import com.orderpilot.api.dto.SupportInternalDtos.ProcessingJobRepairResponse;
import com.orderpilot.api.dto.SupportInternalDtos.SupportAccessGrantResponse;
import com.orderpilot.api.dto.SupportInternalDtos.SupportGrantApprovalDecisionRequest;
import com.orderpilot.api.dto.SupportInternalDtos.SupportTenantDiagnosticsResponse;
import com.orderpilot.api.dto.SupportOperationsDtos.DataRepairOperationsViewResponse;
import com.orderpilot.api.dto.SupportOperationsDtos.SupportOperationsSummaryResponse;
import com.orderpilot.api.dto.SupportOperationsDtos.SupportOperationsTimelineResponse;
import com.orderpilot.api.dto.SupportTenantLocatorDtos.SupportTenantContextResponse;
import com.orderpilot.api.dto.SupportTenantLocatorDtos.SupportTenantSearchResponse;
import com.orderpilot.application.services.support.DataRepairService;
import com.orderpilot.application.services.support.MaintenanceActionService;
import com.orderpilot.application.services.support.ProcessingJobRepairExecutor;
import com.orderpilot.application.services.support.SupportAccessDeniedException;
import com.orderpilot.application.services.support.SupportAccessService;
import com.orderpilot.application.services.support.SupportDiagnosticsService;
import com.orderpilot.application.services.support.SupportOperationsService;
import com.orderpilot.application.services.support.SupportTenantLocatorService;
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
import org.springframework.web.bind.annotation.RequestParam;
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
  private final ProcessingJobRepairExecutor processingJobRepairExecutor;
  private final SupportOperationsService supportOperationsService;
  private final SupportTenantLocatorService supportTenantLocatorService;
  private final RequestActorResolver actorResolver;

  public InternalSupportController(
      SupportAccessService supportAccessService,
      SupportDiagnosticsService diagnosticsService,
      MaintenanceActionService maintenanceActionService,
      DataRepairService dataRepairService,
      ProcessingJobRepairExecutor processingJobRepairExecutor,
      SupportOperationsService supportOperationsService,
      SupportTenantLocatorService supportTenantLocatorService,
      RequestActorResolver actorResolver) {
    this.supportAccessService = supportAccessService;
    this.diagnosticsService = diagnosticsService;
    this.maintenanceActionService = maintenanceActionService;
    this.dataRepairService = dataRepairService;
    this.processingJobRepairExecutor = processingJobRepairExecutor;
    this.supportOperationsService = supportOperationsService;
    this.supportTenantLocatorService = supportTenantLocatorService;
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

  // --- support grant approval workflow (STAFF_SUPPORT_GRANT_APPROVE) ---

  @PostMapping(BASE + "/access-grants/{grantId}/approve")
  public SupportAccessGrantResponse approveGrant(
      @PathVariable UUID grantId,
      @RequestBody(required = false) SupportGrantApprovalDecisionRequest request,
      HttpServletRequest http) {
    UUID tenantId = TenantContext.requireTenantId();
    UUID actor = actorResolver.resolveVerifiedActor(http, tenantId);
    return toGrantResponse(supportAccessService.approveGrant(grantId, tenantId, actor, note(request)));
  }

  @PostMapping(BASE + "/access-grants/{grantId}/reject")
  public SupportAccessGrantResponse rejectGrant(
      @PathVariable UUID grantId,
      @RequestBody(required = false) SupportGrantApprovalDecisionRequest request,
      HttpServletRequest http) {
    UUID tenantId = TenantContext.requireTenantId();
    UUID actor = actorResolver.resolveVerifiedActor(http, tenantId);
    return toGrantResponse(supportAccessService.rejectGrant(grantId, tenantId, actor, note(request)));
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

  // --- OP-CAP-57: read-only internal tenant locator + JIT grant boundary (STAFF_SUPPORT_READ) ---

  // Cross-tenant locator: there is intentionally NO single tenant context here. The staff actor is the
  // trusted, backend-resolved request actor (never a body field); the locator service filters discovery to
  // tenants the actor holds an active DIAGNOSTICS grant for, so a tenant the actor cannot support is never
  // returned. The query/page/size are safe, bounded locator parameters only — never authority.
  @GetMapping(BASE + "/tenants/search")
  public SupportTenantSearchResponse searchTenants(
      @RequestParam(name = "query", defaultValue = "") String query,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size,
      HttpServletRequest http) {
    UUID actor = actorResolver.resolveVerifiedActor(http, TenantContext.getTenantId().orElse(null));
    return supportTenantLocatorService.search(actor, query, page, size);
  }

  // Per-tenant support context for a SELECTED tenant. The path tenant is a navigation handle and must equal
  // the trusted tenant context; the locator service re-validates an active DIAGNOSTICS grant and fails
  // closed (generic denial) when the actor may not support this tenant — it never reveals tenant existence.
  @GetMapping(BASE + "/tenants/{tenantId}/support-context")
  public SupportTenantContextResponse supportContext(@PathVariable UUID tenantId, HttpServletRequest http) {
    UUID contextTenant = requireMatchingTenant(tenantId);
    UUID actor = actorResolver.resolveVerifiedActor(http, contextTenant);
    return supportTenantLocatorService.supportContext(actor, contextTenant);
  }

  // --- read-only support operations visibility (STAFF_SUPPORT_READ + DIAGNOSTICS grant) ---

  @GetMapping(BASE + "/tenants/{tenantId}/operations/summary")
  public SupportOperationsSummaryResponse operationsSummary(@PathVariable UUID tenantId, HttpServletRequest http) {
    UUID contextTenant = requireMatchingTenant(tenantId);
    UUID actor = actorResolver.resolveVerifiedActor(http, contextTenant);
    supportAccessService.authorize(actor, contextTenant, StaffSupportScope.DIAGNOSTICS);
    return supportOperationsService.summary(contextTenant, actor);
  }

  @GetMapping(BASE + "/tenants/{tenantId}/operations/timeline")
  public SupportOperationsTimelineResponse operationsTimeline(
      @PathVariable UUID tenantId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      HttpServletRequest http) {
    UUID contextTenant = requireMatchingTenant(tenantId);
    UUID actor = actorResolver.resolveVerifiedActor(http, contextTenant);
    supportAccessService.authorize(actor, contextTenant, StaffSupportScope.DIAGNOSTICS);
    return supportOperationsService.timeline(contextTenant, actor, page, size);
  }

  @GetMapping(BASE + "/tenants/{tenantId}/data-repair-requests/{requestId}/operations-view")
  public DataRepairOperationsViewResponse dataRepairOperationsView(
      @PathVariable UUID tenantId,
      @PathVariable UUID requestId,
      HttpServletRequest http) {
    UUID contextTenant = requireMatchingTenant(tenantId);
    UUID actor = actorResolver.resolveVerifiedActor(http, contextTenant);
    supportAccessService.authorize(actor, contextTenant, StaffSupportScope.DIAGNOSTICS);
    return supportOperationsService.dataRepairOperationsView(contextTenant, actor, requestId);
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

  // --- data-repair execution approval workflow (OP-CAP-52) ---

  @PostMapping(BASE + "/tenants/{tenantId}/data-repair-requests/{requestId}/request-approval")
  public DataRepairRequestResponse requestDataRepairApproval(
      @PathVariable UUID tenantId,
      @PathVariable UUID requestId,
      @RequestBody DataRepairApprovalRequest request,
      HttpServletRequest http) {
    UUID contextTenant = requireMatchingTenant(tenantId);
    UUID actor = actorResolver.resolveVerifiedActor(http, contextTenant);
    supportAccessService.authorize(actor, contextTenant, StaffSupportScope.DATA_REPAIR);
    return dataRepairService.requestApproval(contextTenant, actor, requestId, request.affectedTargetSummary());
  }

  @PostMapping(BASE + "/tenants/{tenantId}/data-repair-requests/{requestId}/approve")
  public DataRepairRequestResponse approveDataRepair(
      @PathVariable UUID tenantId,
      @PathVariable UUID requestId,
      @RequestBody(required = false) DataRepairApprovalDecisionRequest request,
      HttpServletRequest http) {
    UUID contextTenant = requireMatchingTenant(tenantId);
    UUID actor = actorResolver.resolveVerifiedActor(http, contextTenant);
    supportAccessService.authorize(actor, contextTenant, StaffSupportScope.DATA_REPAIR);
    return dataRepairService.approve(contextTenant, actor, requestId, decisionNote(request));
  }

  @PostMapping(BASE + "/tenants/{tenantId}/data-repair-requests/{requestId}/reject")
  public DataRepairRequestResponse rejectDataRepair(
      @PathVariable UUID tenantId,
      @PathVariable UUID requestId,
      @RequestBody(required = false) DataRepairApprovalDecisionRequest request,
      HttpServletRequest http) {
    UUID contextTenant = requireMatchingTenant(tenantId);
    UUID actor = actorResolver.resolveVerifiedActor(http, contextTenant);
    supportAccessService.authorize(actor, contextTenant, StaffSupportScope.DATA_REPAIR);
    return dataRepairService.reject(contextTenant, actor, requestId, decisionNote(request));
  }

  // OP-CAP-52: the execution STUB. It always fails closed — denied without a valid approval, and
  // execution-disabled even with one — and never mutates a business row or runs SQL/script/connector.
  @PostMapping(BASE + "/tenants/{tenantId}/data-repair-requests/{requestId}/execute")
  public void executeDataRepair(
      @PathVariable UUID tenantId,
      @PathVariable UUID requestId,
      HttpServletRequest http) {
    UUID contextTenant = requireMatchingTenant(tenantId);
    UUID actor = actorResolver.resolveVerifiedActor(http, contextTenant);
    supportAccessService.authorize(actor, contextTenant, StaffSupportScope.DATA_REPAIR);
    dataRepairService.attemptExecution(contextTenant, actor, requestId);
  }

  // OP-CAP-54: the ONE bounded real-execution endpoint. It executes an approved, deterministically-validated
  // processing-job status repair (PROCESSING_JOB_STATUS_REPAIR) — the only data-repair target that may
  // actually mutate a row in this stage, and only this job's own status. It requires the dedicated
  // STAFF_PROCESSING_JOB_REPAIR_EXECUTE permission (route-edge) PLUS a DATA_REPAIR support grant (service
  // layer). The request body is business intent only; the tenant/actor/approval are backend-owned. Every
  // other target keeps using the disabled /execute stub above.
  @PostMapping(BASE + "/tenants/{tenantId}/data-repair-requests/{requestId}/execute-processing-job-repair")
  public ProcessingJobRepairResponse executeProcessingJobRepair(
      @PathVariable UUID tenantId,
      @PathVariable UUID requestId,
      @RequestBody ProcessingJobRepairExecuteRequest request,
      HttpServletRequest http) {
    UUID contextTenant = requireMatchingTenant(tenantId);
    UUID actor = actorResolver.resolveVerifiedActor(http, contextTenant);
    supportAccessService.authorize(actor, contextTenant, StaffSupportScope.DATA_REPAIR);
    return processingJobRepairExecutor.execute(
        contextTenant,
        actor,
        requestId,
        request.processingJobId(),
        request.expectedCurrentStatus(),
        request.desiredStatus(),
        request.reason());
  }

  private static String note(SupportGrantApprovalDecisionRequest request) {
    return request == null ? null : request.decisionNote();
  }

  private static String decisionNote(DataRepairApprovalDecisionRequest request) {
    return request == null ? null : request.decisionNote();
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
        grant.getApprovalStatus().name(),
        grant.getSupportCaseRef(),
        grant.getExpiresAt(),
        grant.getCreatedAt());
  }
}
