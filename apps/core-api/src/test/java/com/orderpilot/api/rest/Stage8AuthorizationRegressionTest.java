package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.application.services.analytics.BusinessValueAnalyticsService;
import com.orderpilot.application.services.analytics.RoiAssumptionsService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.ApiPermissionGuard;
import com.orderpilot.security.ApiPermissionInterceptor;
import com.orderpilot.security.ApiSecurityWebConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OP-CAP-44E — Stage8 authorization regression. Proves the Stage8 value/ROI admin-config mutation is
 * gated by ANALYTICS_MANAGE and that a denied request never reaches the service (no mutation side
 * effect on a rejected call). Complements Stage8ReconciliationSecurityTest, which covers the
 * reconciliation refresh endpoint.
 */
@WebMvcTest(Stage8ValueAnalyticsController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, ApiSecurityWebConfig.class, ApiPermissionInterceptor.class, ApiPermissionGuard.class})
class Stage8AuthorizationRegressionTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private BusinessValueAnalyticsService valueAnalyticsService;
  @MockBean private RoiAssumptionsService assumptionsService;

  @Test
  void roiAssumptionsUpdateWithoutManagePermissionIsDeniedBeforeServiceInvocation() throws Exception {
    mockMvc.perform(put("/api/stage8/value/roi-assumptions")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "ANALYTICS_READ")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden());

    verify(assumptionsService, never()).update(any());
  }

  @Test
  void roiAssumptionsUpdateWithoutAuthenticationIsRejectedBeforeServiceInvocation() throws Exception {
    // No permission header => unauthenticated => Spring Security rejects with 401 before the
    // interceptor even runs. Still fail-closed: the mutation service is never reached.
    mockMvc.perform(put("/api/stage8/value/roi-assumptions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isUnauthorized());

    verify(assumptionsService, never()).update(any());
  }

  @Test
  void roiAssumptionsUpdateWithManagePermissionReachesService() throws Exception {
    mockMvc.perform(put("/api/stage8/value/roi-assumptions")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "ANALYTICS_MANAGE")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk());

    verify(assumptionsService, times(1)).update(any());
  }

  @Test
  void roiAssumptionsReadWithReadPermissionIsAllowed() throws Exception {
    mockMvc.perform(get("/api/stage8/value/roi-assumptions")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "ANALYTICS_READ"))
        .andExpect(status().isOk());

    verify(assumptionsService, times(1)).current();
    verify(assumptionsService, never()).update(any());
  }
}
