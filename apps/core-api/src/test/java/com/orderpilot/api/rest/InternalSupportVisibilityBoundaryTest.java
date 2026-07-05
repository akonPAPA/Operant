package com.orderpilot.api.rest;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.SupportInternalDtos.SupportTenantDiagnosticsResponse;
import com.orderpilot.application.services.analytics.CommerceAnalyticsService;
import com.orderpilot.application.services.support.DataRepairService;
import com.orderpilot.application.services.support.MaintenanceActionService;
import com.orderpilot.application.services.support.ProcessingJobRepairExecutor;
import com.orderpilot.application.services.support.ResolvedStaffPrincipal;
import com.orderpilot.application.services.support.StaffIdentityResolver;
import com.orderpilot.application.services.support.SupportAccessService;
import com.orderpilot.application.services.support.SupportDiagnosticsService;
import com.orderpilot.application.services.support.SupportOperationsService;
import com.orderpilot.application.services.support.SupportTenantLocatorService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.domain.support.StaffRole;
import com.orderpilot.domain.support.StaffSupportScope;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.ApiPermission;
import com.orderpilot.security.ApiPermissionGuard;
import com.orderpilot.security.ApiPermissionInterceptor;
import com.orderpilot.security.ApiRouteSecurityPolicy;
import com.orderpilot.security.ApiSecurityWebConfig;
import com.orderpilot.security.RequestActorResolver;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {InternalSupportController.class, AnalyticsController.class})
@Import({
    CoreConfiguration.class,
    GlobalExceptionHandler.class,
    ApiSecurityWebConfig.class,
    ApiRouteSecurityPolicy.class,
    ApiPermissionInterceptor.class,
    ApiPermissionGuard.class
})
@TestPropertySource(properties = "orderpilot.security.gateway-header-auth.enabled=true")
class InternalSupportVisibilityBoundaryTest {
  private static final UUID TENANT = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
  private static final UUID ACTOR = UUID.fromString("123e4567-e89b-12d3-a456-426614174999");
  private static final String DIAGNOSTICS =
      "/api/v1/internal/support/tenants/" + TENANT + "/diagnostics";
  private static final String MAINTENANCE =
      "/api/v1/internal/support/tenants/" + TENANT + "/maintenance-records";

  @Autowired private MockMvc mockMvc;

  @MockBean private SupportAccessService supportAccessService;
  @MockBean private SupportDiagnosticsService supportDiagnosticsService;
  @MockBean private MaintenanceActionService maintenanceActionService;
  @MockBean private DataRepairService dataRepairService;
  @MockBean private ProcessingJobRepairExecutor processingJobRepairExecutor;
  @MockBean private SupportOperationsService supportOperationsService;
  @MockBean private SupportTenantLocatorService supportTenantLocatorService;
  @MockBean private RequestActorResolver requestActorResolver;
  @MockBean private StaffIdentityResolver staffIdentityResolver;
  @MockBean private CommerceAnalyticsService commerceAnalyticsService;

  @Test
  void averageTenantUserCannotReadInternalSupportAndServicesAreNotInvoked() throws Exception {
    mockMvc.perform(get(DIAGNOSTICS)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, ApiPermission.REVIEW_READ.name()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Missing required API permission STAFF_SUPPORT_READ"));

    verifyNoInteractions(supportAccessService, supportDiagnosticsService);
  }

  @Test
  void tenantAdminCannotMutateOperantMaintenanceAndServiceIsNotInvoked() throws Exception {
    mockMvc.perform(post(MAINTENANCE)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, tenantAdminPermissions())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"actionType":"RUNTIME_DIAGNOSTIC","reason":"tenant-admin-attempt","targetScope":"runtime"}
                """))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Missing required API permission STAFF_MAINTENANCE_RECORD"));

    verifyNoInteractions(supportAccessService, maintenanceActionService);
  }

  @Test
  void validStaffReadWithMatchingTenantReturnsOnlySafeDiagnostics() throws Exception {
    when(staffIdentityResolver.resolveRequired(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new ResolvedStaffPrincipal(
            ACTOR, StaffRole.SUPPORT_VIEWER, "ops@operant",
            ResolvedStaffPrincipal.Source.TRUSTED_GATEWAY_HEADER));
    when(supportDiagnosticsService.diagnose(TENANT))
        .thenReturn(new SupportTenantDiagnosticsResponse(
            TENANT,
            "HEALTHY",
            Map.of("COMPLETED", 3L),
            3,
            Instant.parse("2026-06-30T12:00:00Z"),
            Instant.parse("2026-06-30T12:00:01Z"),
            "DISABLED",
            "TENANT_SAFE_DIAGNOSTICS"));

    mockMvc.perform(get(DIAGNOSTICS)
            .header("X-Tenant-Id", TENANT.toString())
            .header(RequestActorResolver.ACTOR_HEADER, ACTOR.toString())
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, ApiPermission.STAFF_SUPPORT_READ.name()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.health").value("HEALTHY"))
        .andExpect(jsonPath("$.externalExecution").value("DISABLED"))
        .andExpect(jsonPath("$.staffUserId").doesNotExist())
        .andExpect(jsonPath("$.supportGrantId").doesNotExist())
        .andExpect(jsonPath("$.breakGlassRequestId").doesNotExist())
        .andExpect(jsonPath("$.rawTrace").doesNotExist())
        .andExpect(jsonPath("$.stackTrace").doesNotExist())
        .andExpect(jsonPath("$.diagnosticTrace").doesNotExist())
        .andExpect(jsonPath("$.connectorSecret").doesNotExist())
        .andExpect(jsonPath("$.auditEventId").doesNotExist());

    verify(supportAccessService).authorize(ACTOR, TENANT, StaffSupportScope.DIAGNOSTICS);
    verify(supportDiagnosticsService).diagnose(TENANT);
  }

  @Test
  void validTenantAnalyticsReadStillReachesNormalBusinessService() throws Exception {
    mockMvc.perform(get("/api/v1/analytics/overview")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, ApiPermission.ANALYTICS_READ.name()))
        .andExpect(status().isOk());

    verify(commerceAnalyticsService).overview();
  }

  private static String tenantAdminPermissions() {
    return Arrays.stream(ApiPermission.values())
        .filter(permission -> !permission.name().startsWith("STAFF_"))
        .map(Enum::name)
        .collect(Collectors.joining(","));
  }
}
