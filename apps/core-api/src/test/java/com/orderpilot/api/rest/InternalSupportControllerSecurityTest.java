package com.orderpilot.api.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.application.services.support.DataRepairService;
import com.orderpilot.application.services.support.MaintenanceActionService;
import com.orderpilot.application.services.support.ProcessingJobRepairExecutor;
import com.orderpilot.application.services.support.SupportAccessService;
import com.orderpilot.application.services.support.SupportDiagnosticsService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.ApiPermissionGuard;
import com.orderpilot.security.ApiPermissionInterceptor;
import com.orderpilot.security.ApiRouteSecurityPolicy;
import com.orderpilot.security.ApiSecurityWebConfig;
import com.orderpilot.security.RequestActorResolver;
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

  @Autowired private MockMvc mockMvc;

  @MockBean private SupportAccessService supportAccessService;
  @MockBean private SupportDiagnosticsService supportDiagnosticsService;
  @MockBean private MaintenanceActionService maintenanceActionService;
  @MockBean private DataRepairService dataRepairService;
  @MockBean private ProcessingJobRepairExecutor processingJobRepairExecutor;
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
}
