package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.application.services.integration.ChangeRequestService;
import com.orderpilot.application.services.integration.ConnectorExecutionSafetyService;
import com.orderpilot.application.services.integration.ConnectorSyncEventService;
import com.orderpilot.application.services.integration.IntegrationConnectionService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.domain.integration.IntegrationConnection;
import com.orderpilot.domain.integration.IntegrationProviderType;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.ApiPermissionGuard;
import com.orderpilot.security.ApiPermissionInterceptor;
import com.orderpilot.security.ApiSecurityWebConfig;
import com.orderpilot.security.RequestActorResolver;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OP-CAP-44E — Stage9 authorization regression. The Stage9 demo-ERP integration mutation is a
 * non-ChangeRequest Stage9 route; it must NOT bypass the permission interceptor. Proves it is gated by
 * ADMIN_SETTINGS_MANAGE and that a denied request never reaches the integration service (no connector
 * draft/activation side effect on a rejected call).
 */
@WebMvcTest(Stage9IntegrationController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, ApiSecurityWebConfig.class, ApiPermissionInterceptor.class, ApiPermissionGuard.class})
class Stage9AuthorizationRegressionTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private IntegrationConnectionService integrationConnectionService;
  @MockBean private ChangeRequestService changeRequestService;
  @MockBean private ConnectorSyncEventService connectorSyncEventService;
  @MockBean private ConnectorExecutionSafetyService safetyService;
  @MockBean private RequestActorResolver actorResolver;

  @Test
  void demoErpCreateWithReadPermissionIsDeniedBeforeServiceInvocation() throws Exception {
    mockMvc.perform(post("/api/stage9/integrations/demo-erp")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "ADMIN_SETTINGS_READ")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden());

    verify(integrationConnectionService, never()).createDraft(any(), any(), any(), any(), any());
    verify(integrationConnectionService, never()).activate(any());
  }

  @Test
  void demoErpCreateWithoutAuthenticationIsRejectedBeforeServiceInvocation() throws Exception {
    // No permission header => unauthenticated => Spring Security rejects with 401 before the
    // interceptor even runs. Still fail-closed: the connector draft/activation is never reached.
    mockMvc.perform(post("/api/stage9/integrations/demo-erp")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isUnauthorized());

    verify(integrationConnectionService, never()).createDraft(any(), any(), any(), any(), any());
    verify(integrationConnectionService, never()).activate(any());
  }

  @Test
  void demoErpCreateWithManagePermissionReachesService() throws Exception {
    UUID connectionId = UUID.randomUUID();
    IntegrationConnection draft = org.mockito.Mockito.mock(IntegrationConnection.class);
    when(draft.getId()).thenReturn(connectionId);
    IntegrationConnection activated = org.mockito.Mockito.mock(IntegrationConnection.class);
    when(activated.getId()).thenReturn(connectionId);
    when(activated.getProviderType()).thenReturn(IntegrationProviderType.OTHER_ERP);
    when(integrationConnectionService.createDraft(any(), any(), any(), any(), any())).thenReturn(draft);
    when(integrationConnectionService.activate(any())).thenReturn(activated);

    mockMvc.perform(post("/api/stage9/integrations/demo-erp")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "ADMIN_SETTINGS_MANAGE")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk());

    verify(integrationConnectionService, times(1)).createDraft(any(), any(), any(), any(), any());
    verify(integrationConnectionService, times(1)).activate(connectionId);
  }
}
