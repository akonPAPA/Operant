package com.orderpilot.api.rest;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.CommerceIntelligenceDtos.CommerceIntelligenceDemoFlowResponse;
import com.orderpilot.api.dto.CommerceIntelligenceDtos.RuntimeControl;
import com.orderpilot.api.dto.CommerceIntelligenceDtos.Safety;
import com.orderpilot.api.dto.CommerceIntelligenceDtos.Summary;
import com.orderpilot.application.services.commerce.CommerceIntelligenceDemoFlowService;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.ApiPermissionGuard;
import com.orderpilot.security.ApiPermissionInterceptor;
import com.orderpilot.security.ApiSecurityWebConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CommerceIntelligenceController.class)
@Import({
    CoreConfiguration.class,
    ApiSecurityWebConfig.class,
    ApiPermissionInterceptor.class,
    ApiPermissionGuard.class
})
class CommerceIntelligenceControllerTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private CommerceIntelligenceDemoFlowService service;

  @Test
  void analyticsReadPermissionReturnsSafeReadModel() throws Exception {
    when(service.readDemoFlow()).thenReturn(response());

    mockMvc
        .perform(
            get("/api/v1/commerce-intelligence/demo-flow")
                .header(ApiPermissionGuard.PERMISSIONS_HEADER, "ANALYTICS_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.rfqHandoffsTotal").value(4))
        .andExpect(jsonPath("$.safety.externalExecutionStatus").value("DISABLED"))
        .andExpect(jsonPath("$.runtimeControl.guarded").value(true))
        .andExpect(
            jsonPath("$.runtimeControl.denialTelemetry").value("NOT_MEASURED"));
  }

  @Test
  void missingPermissionIsDeniedBeforeServiceInvocation() throws Exception {
    mockMvc
        .perform(get("/api/v1/commerce-intelligence/demo-flow"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
        .andExpect(jsonPath("$.message").value("Authentication required"));

    verifyNoInteractions(service);
  }

  @Test
  void unrelatedAndSupportPermissionsAreDeniedBeforeServiceInvocation() throws Exception {
    for (String permission : List.of("REVIEW_READ", "ADMIN_SETTINGS_READ", "STAFF_SUPPORT_READ")) {
      mockMvc
          .perform(
              get("/api/v1/commerce-intelligence/demo-flow")
                  .header(ApiPermissionGuard.PERMISSIONS_HEADER, permission))
          .andExpect(status().isForbidden())
          .andExpect(
              jsonPath("$.message").value("Missing required API permission ANALYTICS_READ"));
    }

    verifyNoInteractions(service);
  }

  private static CommerceIntelligenceDemoFlowResponse response() {
    return new CommerceIntelligenceDemoFlowResponse(
        Instant.parse("2026-07-05T00:00:00Z"),
        "Tenant-observed demo flow (all retained records)",
        new Summary(4, 1, 1, 1, 1, 1, 1, 1, 1, 0),
        new Safety(
            "DISABLED",
            "NOT_INVOKED",
            "NOT_REQUESTED",
            null,
            null,
            null,
            "NOT_MEASURED",
            "Safe demo contract; row counts are not measured.",
            List.of("Not measured.")),
        new RuntimeControl(
            true,
            "RATE_BACKPRESSURE_GATED",
            "AI_VALIDATION_EXPLANATION_GUARDED",
            "RATE_BACKPRESSURE_GATED",
            "RATE_BACKPRESSURE_GATED",
            "NOT_APPLICABLE_FOR_DEMO_OPS",
            "NOT_MEASURED",
            "Safe posture only."),
        List.of(),
        List.of(),
        List.of());
  }
}
