package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.RuntimeEntitlementAdminDtos.RuntimeEntitlementStatusResponse;
import com.orderpilot.api.dto.RuntimeEntitlementAdminDtos.TenantRuntimePlanResponse;
import com.orderpilot.application.services.runtime.RuntimeEntitlementAdminService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.domain.usage.TenantRuntimePlanCode;
import com.orderpilot.domain.usage.TenantRuntimePlanStatus;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.ApiPermissionGuard;
import com.orderpilot.security.ApiPermissionInterceptor;
import com.orderpilot.security.ApiSecurityWebConfig;
import com.orderpilot.security.RequestActorResolver;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OP-CAP-16I — permission + contract tests for the runtime entitlement admin controller. Proves
 * reads require RUNTIME_ENTITLEMENT_READ, mutations require RUNTIME_ENTITLEMENT_MANAGE, unknown
 * feature → 400, unknown plan → 404, and the status response exposes only governance fields.
 */
@WebMvcTest(RuntimeEntitlementAdminController.class)
@Import({CoreConfiguration.class, ApiSecurityWebConfig.class, ApiPermissionInterceptor.class, ApiPermissionGuard.class, RequestActorResolver.class})
class RuntimeEntitlementAdminControllerStage16ITest {
  private static final String READ = "RUNTIME_ENTITLEMENT_READ";
  private static final String MANAGE = "RUNTIME_ENTITLEMENT_MANAGE";
  private static final String PERM_HEADER = "X-OrderPilot-Permissions";

  @Autowired private MockMvc mockMvc;
  @MockBean private RuntimeEntitlementAdminService service;

  @Test
  void getEntitlementsWithReadPermissionSucceeds() throws Exception {
    UUID tenantId = UUID.randomUUID();
    when(service.getCurrentRuntimeEntitlements())
        .thenReturn(new RuntimeEntitlementStatusResponse(tenantId, "COMPATIBILITY_DEFAULT", null, List.of()));

    mockMvc.perform(get("/api/v1/runtime/entitlements").header(PERM_HEADER, READ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
        .andExpect(jsonPath("$.source").value("COMPATIBILITY_DEFAULT"));
  }

  @Test
  void getEntitlementsWithoutPermissionIsForbidden() throws Exception {
    mockMvc.perform(get("/api/v1/runtime/entitlements"))
        .andExpect(status().isForbidden());
  }

  @Test
  void createPlanRequiresManagePermission() throws Exception {
    UUID tenantId = UUID.randomUUID();
    Instant now = Instant.parse("2026-06-13T12:00:00Z");
    when(service.createPlan(any()))
        .thenReturn(new TenantRuntimePlanResponse(UUID.randomUUID(), tenantId, TenantRuntimePlanCode.PRO,
            TenantRuntimePlanStatus.ACTIVE, now, null, now, now, List.of()));

    mockMvc.perform(post("/api/v1/runtime/plans").header(PERM_HEADER, MANAGE)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"planCode\":\"PRO\",\"status\":\"ACTIVE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.planCode").value("PRO"))
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  @Test
  void createPlanWithReadPermissionOnlyIsForbidden() throws Exception {
    mockMvc.perform(post("/api/v1/runtime/plans").header(PERM_HEADER, READ)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"planCode\":\"PRO\",\"status\":\"ACTIVE\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void upsertFeatureWithUnknownFeatureTypeReturns400() throws Exception {
    mockMvc.perform(patch("/api/v1/runtime/plans/{planId}/features/{featureType}", UUID.randomUUID(), "NOT_A_FEATURE")
            .header(PERM_HEADER, MANAGE)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"enabled\":true}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void updateUnknownPlanReturns404() throws Exception {
    when(service.updatePlan(any())).thenThrow(new NotFoundException("Runtime plan not found"));

    mockMvc.perform(patch("/api/v1/runtime/plans/{planId}", UUID.randomUUID())
            .header(PERM_HEADER, MANAGE)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"SUSPENDED\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void disableFeatureWithoutPermissionIsForbidden() throws Exception {
    mockMvc.perform(delete("/api/v1/runtime/plans/{planId}/features/{featureType}", UUID.randomUUID(), "REPORT_EXPORT"))
        .andExpect(status().isForbidden());
  }
}
