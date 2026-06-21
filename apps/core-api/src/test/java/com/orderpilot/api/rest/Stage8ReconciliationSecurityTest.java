package com.orderpilot.api.rest;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.application.services.reconciliation.InventoryReconciliationService;
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
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(Stage8ReconciliationController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, ApiSecurityWebConfig.class, ApiPermissionInterceptor.class, ApiPermissionGuard.class})
class Stage8ReconciliationSecurityTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private InventoryReconciliationService service;

  @Test
  void refreshRejectsReadOnlyPermissionBeforeServiceInvocation() throws Exception {
    mockMvc.perform(post("/api/stage8/reconciliation/refresh")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "ANALYTICS_READ"))
        .andExpect(status().isForbidden());

    verify(service, never()).refreshProjections();
  }
}
