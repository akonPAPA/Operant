package com.orderpilot.api.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orderpilot.api.dto.SupportOperationsDtos.SupportOperationsSummaryResponse;
import com.orderpilot.application.services.support.DataRepairService;
import com.orderpilot.application.services.support.MaintenanceActionService;
import com.orderpilot.application.services.support.ProcessingJobRepairExecutor;
import com.orderpilot.application.services.support.SupportAccessService;
import com.orderpilot.application.services.support.SupportDiagnosticsService;
import com.orderpilot.application.services.support.SupportOperationsService;
import com.orderpilot.application.services.support.SupportTenantLocatorService;
import com.orderpilot.api.dto.SupportTenantLocatorDtos.SupportTenantContextResponse;
import com.orderpilot.api.dto.SupportTenantLocatorDtos.SupportTenantSearchResponse;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.domain.support.StaffSupportScope;
import com.orderpilot.security.ApiPermissionGuard;
import com.orderpilot.security.ApiPermissionInterceptor;
import com.orderpilot.security.ApiRouteSecurityPolicy;
import com.orderpilot.security.ApiSecurityWebConfig;
import com.orderpilot.security.RequestActorResolver;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OP-CAP-51 — HTTP-level security proof for the internal support surface through the real security stack.
 * The internal/support routes are never public: an unauthenticated request is 401, and an authenticated
 * request that carries a tenant operator/admin permission (but not the dedicated STAFF_* permission) is a
 * stable 403. The deny happens at the route-edge interceptor, so the (mocked) services are never reached.
 */
@WebMvcTest(controllers = InternalSupportController.class)
@Import({
    CoreConfiguration.class,
    GlobalExceptionHandler.class,
    ApiSecurityWebConfig.class,
    ApiRouteSecurityPolicy.class,
    ApiPermissionInterceptor.class,
    ApiPermissionGuard.class
})
@TestPropertySource(properties = "orderpilot.security.gateway-header-auth.enabled=true")
class InternalSupportControllerSecurityTest {
  private static final String DIAGNOSTICS =
      "/api/v1/internal/support/tenants/123e4567-e89b-12d3-a456-426614174000/diagnostics";
  private static final UUID TENANT_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
  private static final UUID ACTOR_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174999");
  private static final String DATA_REPAIR =
      "/api/v1/internal/support/tenants/123e4567-e89b-12d3-a456-426614174000/data-repair-requests/dry-run";
  private static final String GRANTS = "/api/v1/internal/support/access-grants";
  private static final String GRANT_APPROVE =
      GRANTS + "/123e4567-e89b-12d3-a456-426614174111/approve";
  private static final String DATA_REPAIR_EXECUTE =
      "/api/v1/internal/support/tenants/123e4567-e89b-12d3-a456-426614174000/data-repair-requests/"
          + "123e4567-e89b-12d3-a456-426614174222/execute";
  private static final String PROCESSING_JOB_REPAIR_EXECUTE =
      "/api/v1/internal/support/tenants/123e4567-e89b-12d3-a456-426614174000/data-repair-requests/"
          + "123e4567-e89b-12d3-a456-426614174222/execute-processing-job-repair";
  private static final String OPERATIONS_SUMMARY =
      "/api/v1/internal/support/tenants/123e4567-e89b-12d3-a456-426614174000/operations/summary";
  private static final String OPERATIONS_TIMELINE =
      "/api/v1/internal/support/tenants/123e4567-e89b-12d3-a456-426614174000/operations/timeline";
  private static final String DATA_REPAIR_OPERATIONS_VIEW =
      "/api/v1/internal/support/tenants/123e4567-e89b-12d3-a456-426614174000/data-repair-requests/"
          + "123e4567-e89b-12d3-a456-426614174222/operations-view";
  private static final String TENANT_SEARCH = "/api/v1/internal/support/tenants/search";
  private static final String SUPPORT_CONTEXT =
      "/api/v1/internal/support/tenants/123e4567-e89b-12d3-a456-426614174000/support-context";

  @Autowired private MockMvc mockMvc;

  @MockBean private SupportAccessService supportAccessService;
  @MockBean private SupportDiagnosticsService supportDiagnosticsService;
  @MockBean private MaintenanceActionService maintenanceActionService;
  @MockBean private DataRepairService dataRepairService;
  @MockBean private ProcessingJobRepairExecutor processingJobRepairExecutor;
  @MockBean private SupportOperationsService supportOperationsService;
  @MockBean private SupportTenantLocatorService supportTenantLocatorService;
  @MockBean private RequestActorResolver requestActorResolver;

  @Test
  void diagnosticsRouteIsNotPublic_unauthenticatedReturns401() throws Exception {
    mockMvc.perform(get(DIAGNOSTICS))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void diagnosticsRouteRejectsTenantOperatorPermissionWith403() throws Exception {
    mockMvc.perform(get(DIAGNOSTICS).header(ApiPermissionGuard.PERMISSIONS_HEADER, "REVIEW_READ"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_POLICY_DENIED"))
        .andExpect(jsonPath("$.message").value("Missing required API permission STAFF_SUPPORT_READ"));
  }

  @Test
  void grantCreationRejectsStaffSupportReadAloneWith403() throws Exception {
    mockMvc.perform(post(GRANTS)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "STAFF_SUPPORT_READ")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Missing required API permission STAFF_SUPPORT_GRANT_MANAGE"));
  }

  @Test
  void dataRepairDryRunRejectsMaintenancePermissionAloneWith403() throws Exception {
    mockMvc.perform(post(DATA_REPAIR)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "STAFF_MAINTENANCE_RECORD")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Missing required API permission STAFF_DATA_REPAIR_DRYRUN"));
  }

  @Test
  void dataRepairDryRunRouteIsNotPublic_unauthenticatedReturns401() throws Exception {
    mockMvc.perform(post(DATA_REPAIR).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  // --- OP-CAP-52 approval/execution endpoints are not public and require the dedicated STAFF_* permission ---

  @Test
  void grantApproveRouteIsNotPublic_unauthenticatedReturns401() throws Exception {
    mockMvc.perform(post(GRANT_APPROVE).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void grantApproveRejectsGrantManageAloneWith403() throws Exception {
    // The actor who can create grants cannot approve one without the dedicated approve permission.
    mockMvc.perform(post(GRANT_APPROVE)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "STAFF_SUPPORT_GRANT_MANAGE")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Missing required API permission STAFF_SUPPORT_GRANT_APPROVE"));
  }

  @Test
  void grantApproveRejectsTenantOperatorPermissionWith403() throws Exception {
    mockMvc.perform(post(GRANT_APPROVE)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "REVIEW_ACTION")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Missing required API permission STAFF_SUPPORT_GRANT_APPROVE"));
  }

  @Test
  void dataRepairExecuteRouteIsNotPublic_unauthenticatedReturns401() throws Exception {
    mockMvc.perform(post(DATA_REPAIR_EXECUTE).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void dataRepairExecuteRejectsWeakerSupportPermissionsWith403() throws Exception {
    mockMvc.perform(post(DATA_REPAIR_EXECUTE)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "STAFF_DATA_REPAIR_APPROVE")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message")
            .value("Missing required API permission STAFF_DATA_REPAIR_EXECUTION_ATTEMPT"));
  }

  // --- OP-CAP-54: the bounded processing-job repair endpoint is not public and needs its own permission ---

  @Test
  void processingJobRepairExecuteRouteIsNotPublic_unauthenticatedReturns401() throws Exception {
    mockMvc.perform(post(PROCESSING_JOB_REPAIR_EXECUTE).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void processingJobRepairExecuteRejectsGenericExecuteStubPermissionWith403() throws Exception {
    // The OP-CAP-52 generic execute-stub permission cannot reach the real bounded executor.
    mockMvc.perform(post(PROCESSING_JOB_REPAIR_EXECUTE)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "STAFF_DATA_REPAIR_EXECUTION_ATTEMPT")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message")
            .value("Missing required API permission STAFF_PROCESSING_JOB_REPAIR_EXECUTE"));
  }

  @Test
  void processingJobRepairExecuteRejectsTenantOperatorPermissionWith403() throws Exception {
    mockMvc.perform(post(PROCESSING_JOB_REPAIR_EXECUTE)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "REVIEW_ACTION")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message")
            .value("Missing required API permission STAFF_PROCESSING_JOB_REPAIR_EXECUTE"));
  }

  // --- OP-CAP-55: read-only operations visibility is internal support read, never tenant-visible ---

  @Test
  void operationsSummaryRouteIsNotPublic_unauthenticatedReturns401() throws Exception {
    mockMvc.perform(get(OPERATIONS_SUMMARY))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void operationsSummaryRejectsTenantAdminPermissionWith403() throws Exception {
    mockMvc.perform(get(OPERATIONS_SUMMARY).header(ApiPermissionGuard.PERMISSIONS_HEADER, "ADMIN_SETTINGS_READ"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Missing required API permission STAFF_SUPPORT_READ"));
  }

  @Test
  void operationsTimelineRejectsStaffWithoutReadPermissionWith403() throws Exception {
    mockMvc.perform(get(OPERATIONS_TIMELINE)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "STAFF_DATA_REPAIR_DRYRUN"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Missing required API permission STAFF_SUPPORT_READ"));
  }

  @Test
  void dataRepairOperationsViewRejectsTenantOperatorPermissionWith403() throws Exception {
    mockMvc.perform(get(DATA_REPAIR_OPERATIONS_VIEW)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "REVIEW_READ"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Missing required API permission STAFF_SUPPORT_READ"));
  }

  @Test
  void operationsSummaryWithStaffReadAndDiagnosticsGrantReturnsSafeSummary() throws Exception {
    when(requestActorResolver.resolveVerifiedActor(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(TENANT_ID)))
        .thenReturn(ACTOR_ID);
    when(supportOperationsService.summary(TENANT_ID, ACTOR_ID))
        .thenReturn(new SupportOperationsSummaryResponse(
            TENANT_ID,
            2,
            1,
            3,
            4,
            5,
            6,
            7,
            8,
            9,
            10,
            Instant.parse("2026-06-26T12:00:00Z"),
            Instant.parse("2026-06-26T12:00:01Z"),
            "DISABLED"));

    mockMvc.perform(get(OPERATIONS_SUMMARY)
            .header("X-Tenant-Id", TENANT_ID.toString())
            .header(RequestActorResolver.ACTOR_HEADER, ACTOR_ID.toString())
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "STAFF_SUPPORT_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.openIncidents").value(2))
        .andExpect(jsonPath("$.criticalOpenIncidents").value(1))
        .andExpect(jsonPath("$.externalExecution").value("DISABLED"))
        .andExpect(jsonPath("$.payload").doesNotExist())
        .andExpect(jsonPath("$.secret").doesNotExist())
        .andExpect(jsonPath("$.actorId").doesNotExist());

    verify(supportAccessService).authorize(ACTOR_ID, TENANT_ID, StaffSupportScope.DIAGNOSTICS);
    verify(supportOperationsService).summary(TENANT_ID, ACTOR_ID);
  }

  @Test
  void operationsSummaryWithWrongTenantHeaderIsDeniedBeforeServiceRead() throws Exception {
    mockMvc.perform(get(OPERATIONS_SUMMARY)
            .header("X-Tenant-Id", UUID.randomUUID().toString())
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "STAFF_SUPPORT_READ"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUPPORT_ACCESS_DENIED"));
  }

  // --- OP-CAP-57: the tenant locator + support context are internal support read, never tenant-visible ---

  @Test
  void tenantLocatorSearchRouteIsNotPublic_unauthenticatedReturns401() throws Exception {
    mockMvc.perform(get(TENANT_SEARCH))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void tenantLocatorSearchRejectsTenantOperatorPermissionWith403() throws Exception {
    mockMvc.perform(get(TENANT_SEARCH).header(ApiPermissionGuard.PERMISSIONS_HEADER, "ADMIN_SETTINGS_READ"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Missing required API permission STAFF_SUPPORT_READ"));
  }

  @Test
  void tenantLocatorSearchWithStaffReadReturnsBoundedSafeResults() throws Exception {
    when(requestActorResolver.resolveVerifiedActor(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(ACTOR_ID);
    when(supportTenantLocatorService.search(ACTOR_ID, "acme", 0, 20))
        .thenReturn(new SupportTenantSearchResponse(
            "acme", 0, 20, 0, false, java.util.List.of(), Instant.parse("2026-06-26T12:00:01Z")));

    mockMvc.perform(get(TENANT_SEARCH)
            .param("query", "acme")
            .header(RequestActorResolver.ACTOR_HEADER, ACTOR_ID.toString())
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "STAFF_SUPPORT_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.pageSize").value(20))
        .andExpect(jsonPath("$.returnedCount").value(0))
        .andExpect(jsonPath("$.actorId").doesNotExist())
        .andExpect(jsonPath("$.secret").doesNotExist());

    verify(supportTenantLocatorService).search(ACTOR_ID, "acme", 0, 20);
  }

  @Test
  void supportContextRouteIsNotPublic_unauthenticatedReturns401() throws Exception {
    mockMvc.perform(get(SUPPORT_CONTEXT))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void supportContextRejectsTenantOperatorPermissionWith403() throws Exception {
    mockMvc.perform(get(SUPPORT_CONTEXT).header(ApiPermissionGuard.PERMISSIONS_HEADER, "REVIEW_READ"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Missing required API permission STAFF_SUPPORT_READ"));
  }

  @Test
  void supportContextWithWrongTenantHeaderIsDeniedBeforeServiceRead() throws Exception {
    mockMvc.perform(get(SUPPORT_CONTEXT)
            .header("X-Tenant-Id", UUID.randomUUID().toString())
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "STAFF_SUPPORT_READ"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUPPORT_ACCESS_DENIED"));
  }

  @Test
  void supportContextWithStaffReadAndMatchingTenantReturnsSafeContext() throws Exception {
    when(requestActorResolver.resolveVerifiedActor(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(TENANT_ID)))
        .thenReturn(ACTOR_ID);
    when(supportTenantLocatorService.supportContext(ACTOR_ID, TENANT_ID))
        .thenReturn(new SupportTenantContextResponse(
            TENANT_ID,
            "Acme Distribution",
            "acme",
            "ACTIVE",
            java.util.List.of("DIAGNOSTICS"),
            Instant.parse("2026-06-26T18:00:00Z"),
            true,
            true,
            "DISABLED",
            Instant.parse("2026-06-26T12:00:01Z")));

    mockMvc.perform(get(SUPPORT_CONTEXT)
            .header("X-Tenant-Id", TENANT_ID.toString())
            .header(RequestActorResolver.ACTOR_HEADER, ACTOR_ID.toString())
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "STAFF_SUPPORT_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("Acme Distribution"))
        .andExpect(jsonPath("$.canViewOperations").value(true))
        .andExpect(jsonPath("$.externalExecution").value("DISABLED"))
        .andExpect(jsonPath("$.actorId").doesNotExist())
        .andExpect(jsonPath("$.secret").doesNotExist());

    verify(supportTenantLocatorService).supportContext(ACTOR_ID, TENANT_ID);
  }
}
