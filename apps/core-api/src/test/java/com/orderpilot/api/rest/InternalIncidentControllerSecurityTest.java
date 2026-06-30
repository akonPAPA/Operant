package com.orderpilot.api.rest;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.application.services.incident.IncidentResponseService;
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
 * OP-CAP-53 — HTTP-level security proof for the internal incident-response / break-glass surface through the
 * real security stack. The routes are never public: an unauthenticated request is 401, and an authenticated
 * request carrying a tenant operator/admin permission (or a weaker/wrong STAFF_* permission) is a stable 403.
 * The deny happens at the route-edge interceptor, so the (mocked) service is never reached, and distinct
 * verbs require distinct STAFF_* permissions.
 */
@WebMvcTest(controllers = InternalIncidentController.class)
@Import({
    CoreConfiguration.class,
    GlobalExceptionHandler.class,
    ApiSecurityWebConfig.class,
    ApiRouteSecurityPolicy.class,
    ApiPermissionInterceptor.class,
    ApiPermissionGuard.class
})
@TestPropertySource(properties = "orderpilot.security.gateway-header-auth.enabled=true")
class InternalIncidentControllerSecurityTest {
  private static final String TENANT = "123e4567-e89b-12d3-a456-426614174000";
  private static final String INC = "223e4567-e89b-12d3-a456-426614174111";
  private static final String REQ = "323e4567-e89b-12d3-a456-426614174222";

  private static final String INCIDENTS = "/api/v1/internal/support/incidents";
  private static final String INCIDENT_CLOSE = INCIDENTS + "/" + INC + "/close";
  private static final String BREAK_GLASS_CREATE =
      "/api/v1/internal/support/tenants/" + TENANT + "/incidents/" + INC + "/break-glass-requests";
  private static final String BG_APPROVE =
      "/api/v1/internal/support/tenants/" + TENANT + "/break-glass-requests/" + REQ + "/approve";
  private static final String BG_REVOKE =
      "/api/v1/internal/support/tenants/" + TENANT + "/break-glass-requests/" + REQ + "/revoke";

  @Autowired private MockMvc mockMvc;

  @MockBean private IncidentResponseService incidentResponseService;
  @MockBean private RequestActorResolver requestActorResolver;

  @Test
  void createIncidentRouteIsNotPublic_unauthenticatedReturns401() throws Exception {
    mockMvc.perform(post(INCIDENTS).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void getIncidentRouteIsNotPublic_unauthenticatedReturns401() throws Exception {
    mockMvc.perform(get(INCIDENTS + "/" + INC))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void createIncidentRejectsTenantOperatorPermissionWith403() throws Exception {
    mockMvc.perform(post(INCIDENTS)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "REVIEW_ACTION")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_POLICY_DENIED"))
        .andExpect(jsonPath("$.message").value("Missing required API permission STAFF_INCIDENT_CREATE"));
    verifyNoInteractions(incidentResponseService);
  }

  @Test
  void closeIncidentRejectsIncidentCreateAloneWith403() throws Exception {
    mockMvc.perform(post(INCIDENT_CLOSE)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "STAFF_INCIDENT_CREATE")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Missing required API permission STAFF_INCIDENT_CLOSE"));
  }

  @Test
  void requestBreakGlassRouteIsNotPublic_unauthenticatedReturns401() throws Exception {
    mockMvc.perform(post(BREAK_GLASS_CREATE).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void requestBreakGlassRejectsIncidentCreatePermissionWith403() throws Exception {
    mockMvc.perform(post(BREAK_GLASS_CREATE)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "STAFF_INCIDENT_CREATE")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Missing required API permission STAFF_BREAK_GLASS_REQUEST"));
    verifyNoInteractions(incidentResponseService);
  }

  @Test
  void requestBreakGlassRejectsTenantAdminPermissionWithoutMutation() throws Exception {
    mockMvc.perform(post(BREAK_GLASS_CREATE)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "ADMIN_SETTINGS_MANAGE")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Missing required API permission STAFF_BREAK_GLASS_REQUEST"));
    verifyNoInteractions(incidentResponseService);
  }

  @Test
  void requestBreakGlassRejectsWrongTenantBeforeMutation() throws Exception {
    mockMvc.perform(post(BREAK_GLASS_CREATE)
            .header("X-Tenant-Id", "423e4567-e89b-12d3-a456-426614174333")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "STAFF_BREAK_GLASS_REQUEST")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"scope":"INCIDENT_DIAGNOSTICS","reason":"case-123","ttlSeconds":600}
                """))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUPPORT_ACCESS_DENIED"));
    verifyNoInteractions(incidentResponseService);
  }

  @Test
  void approveBreakGlassRejectsRequestPermissionAloneWith403() throws Exception {
    // The requester cannot self-approve from the request permission.
    mockMvc.perform(post(BG_APPROVE)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "STAFF_BREAK_GLASS_REQUEST")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Missing required API permission STAFF_BREAK_GLASS_APPROVE"));
  }

  @Test
  void revokeBreakGlassRejectsApprovePermissionAloneWith403() throws Exception {
    mockMvc.perform(post(BG_REVOKE)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "STAFF_BREAK_GLASS_APPROVE")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Missing required API permission STAFF_BREAK_GLASS_REVOKE"));
  }
}
