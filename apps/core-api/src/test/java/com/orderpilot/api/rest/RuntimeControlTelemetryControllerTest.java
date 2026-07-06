package com.orderpilot.api.rest;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.RuntimeControlTelemetryDtos.AdmissionPosture;
import com.orderpilot.api.dto.RuntimeControlTelemetryDtos.NotMeasured;
import com.orderpilot.api.dto.RuntimeControlTelemetryDtos.ProvenGuarantee;
import com.orderpilot.api.dto.RuntimeControlTelemetryDtos.RuntimeControlDemoFlowTelemetryResponse;
import com.orderpilot.api.dto.RuntimeControlTelemetryDtos.SafetyPosture;
import com.orderpilot.api.dto.RuntimeControlTelemetryDtos.TelemetryValue;
import com.orderpilot.api.dto.RuntimeControlTelemetryDtos.WorkloadPosture;
import com.orderpilot.application.services.runtime.RuntimeControlDemoFlowTelemetryService;
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

/**
 * OP-CAP-27D — proves the runtime-control telemetry read endpoint is gated by ANALYTICS_READ (denied
 * before the service runs for a missing/wrong permission), ignores all client-supplied authority, and
 * returns honest MEASURED/STATIC_CONTRACT/NOT_MEASURED labels.
 */
@WebMvcTest(RuntimeControlTelemetryController.class)
@Import({
    CoreConfiguration.class,
    ApiSecurityWebConfig.class,
    ApiPermissionInterceptor.class,
    ApiPermissionGuard.class
})
class RuntimeControlTelemetryControllerTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private RuntimeControlDemoFlowTelemetryService service;

  @Test
  void analyticsReadPermissionReturnsSafeTelemetry() throws Exception {
    when(service.readDemoFlowTelemetry()).thenReturn(response());

    mockMvc
        .perform(
            get("/api/v1/runtime-control/demo-flow")
                .header(ApiPermissionGuard.PERMISSIONS_HEADER, "ANALYTICS_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.safety.runtimeControlView").value("READ_ONLY"))
        .andExpect(jsonPath("$.safety.externalExecution").value("DISABLED"))
        .andExpect(jsonPath("$.safety.guardEvaluation").value("NOT_INVOKED_BY_THIS_READ"))
        .andExpect(jsonPath("$.admission.maxCostUnitsPerRequest.kind").value("STATIC_CONTRACT"))
        .andExpect(jsonPath("$.admission.deniedCount.kind").value("NOT_MEASURED"))
        .andExpect(jsonPath("$.admission.deniedCount.value").doesNotExist());

    verify(service).readDemoFlowTelemetry();
    verifyNoMoreInteractions(service);
  }

  @Test
  void clientSuppliedAuthorityAndBodyAreIgnoredByTheReadEndpoint() throws Exception {
    when(service.readDemoFlowTelemetry()).thenReturn(response());

    // The client attempts to smuggle tenant/actor/source/status/runtime authority via query params and
    // a request body. The endpoint declares no @RequestParam/@RequestBody, so the extras are inert: the
    // service still receives a no-argument, trusted-context-only read call.
    mockMvc
        .perform(
            get("/api/v1/runtime-control/demo-flow")
                .header(ApiPermissionGuard.PERMISSIONS_HEADER, "ANALYTICS_READ")
                .param("tenantId", "22222222-2222-2222-2222-222222222222")
                .param("actorId", "33333333-3333-3333-3333-333333333333")
                .param("sourceId", "44444444-4444-4444-4444-444444444444")
                .param("status", "DEMO_COMPLETED")
                .param("runtimeMode", "DISABLED")
                .param("runtimeDecision", "ALLOW")
                .param("approvalStatus", "APPROVED")
                .param("executionStatus", "EXECUTED")
                .contentType("application/json")
                .content(
                    "{\"tenantId\":\"22222222-2222-2222-2222-222222222222\","
                        + "\"deniedCount\":9999}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.admission.deniedCount.kind").value("NOT_MEASURED"))
        .andExpect(jsonPath("$.admission.deniedCount.value").doesNotExist());

    verify(service).readDemoFlowTelemetry();
    verifyNoMoreInteractions(service);
  }

  @Test
  void missingPermissionIsDeniedBeforeServiceInvocation() throws Exception {
    mockMvc
        .perform(get("/api/v1/runtime-control/demo-flow"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
        .andExpect(jsonPath("$.message").value("Authentication required"));

    verifyNoInteractions(service);
  }

  @Test
  void wrongUnrelatedAndStaffPermissionsAreDeniedBeforeServiceInvocation() throws Exception {
    // Wrong tenant permissions AND the platform runtime-governance permission must NOT satisfy this
    // operator read (proving the /runtime-control edge does not inherit RUNTIME_ENTITLEMENT_READ), and
    // no Operant staff/support permission may reach it either.
    for (String permission :
        List.of("REVIEW_READ", "ADMIN_SETTINGS_READ", "RUNTIME_ENTITLEMENT_READ", "STAFF_SUPPORT_READ")) {
      mockMvc
          .perform(
              get("/api/v1/runtime-control/demo-flow")
                  .header(ApiPermissionGuard.PERMISSIONS_HEADER, permission))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.message").value("Missing required API permission ANALYTICS_READ"));
    }

    verifyNoInteractions(service);
  }

  @Test
  void nonGetVerbsNeverReachServiceRegardlessOfSuppliedPermission() throws Exception {
    // PR #253: there is no runtime-control write endpoint. A POST — even carrying the platform
    // runtime-governance permission, a staff permission, or the correct read permission — must never
    // reach the service (no handler + fail-closed route policy). Proves the /runtime-control edge does
    // not expose any mutation surface and cannot be reached by smuggling RUNTIME_ENTITLEMENT_MANAGE.
    for (String permission :
        List.of("RUNTIME_ENTITLEMENT_MANAGE", "STAFF_SUPPORT_READ", "ANALYTICS_READ")) {
      mockMvc.perform(
          post("/api/v1/runtime-control/demo-flow")
              .header(ApiPermissionGuard.PERMISSIONS_HEADER, permission));
    }

    verifyNoInteractions(service);
  }

  private static RuntimeControlDemoFlowTelemetryResponse response() {
    return new RuntimeControlDemoFlowTelemetryResponse(
        Instant.parse("2026-07-05T00:00:00Z"),
        "Runtime-control posture for the RFQ/AI/demo path only",
        new SafetyPosture(
            "READ_ONLY",
            "NOT_INVOKED",
            "DISABLED",
            "NOT_INVOKED_BY_THIS_READ",
            "PARTIAL",
            "Read-only runtime-control posture."),
        List.of(
            new WorkloadPosture(
                "DEMO_RFQ_HANDOFF_CREATE",
                "Demo RFQ handoff creation",
                TelemetryValue.staticContract("DETERMINISTIC_DEMO_OP", "not AI"),
                TelemetryValue.staticContract("SYNC", "sync"),
                TelemetryValue.staticContract("CHEAP_PATH", "cheap"),
                TelemetryValue.staticContract("RATE_BACKPRESSURE_GATED", "rate gated"))),
        new AdmissionPosture(
            TelemetryValue.staticContract("ENABLED", "enabled"),
            TelemetryValue.staticContract("ENABLED", "ai enabled"),
            TelemetryValue.staticContract("10000", "max cost"),
            TelemetryValue.staticContract("100", "max sync"),
            TelemetryValue.staticContract("1000", "queue depth"),
            TelemetryValue.notMeasured("no admitted counter"),
            TelemetryValue.notMeasured("no denied counter")),
        List.of(new ProvenGuarantee("X", "x", "x")),
        List.of(new NotMeasured("Y", "y", "y")));
  }
}
