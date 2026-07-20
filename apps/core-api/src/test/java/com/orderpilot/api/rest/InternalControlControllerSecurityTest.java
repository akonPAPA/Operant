package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.ControlInternalDtos.ControlDiagnosticsResponse;
import com.orderpilot.api.dto.ControlInternalDtos.ControlHealthResponse;
import com.orderpilot.api.dto.ControlInternalDtos.ControlReadinessResponse;
import com.orderpilot.api.dto.ControlInternalDtos.ControlStatusResponse;
import com.orderpilot.api.dto.ControlInternalDtos.DatabaseDiagnostics;
import com.orderpilot.api.dto.ControlInternalDtos.DependencyState;
import com.orderpilot.api.dto.ControlInternalDtos.DependencyStatus;
import com.orderpilot.api.dto.ControlInternalDtos.JvmDiagnostics;
import com.orderpilot.api.dto.ControlInternalDtos.OperationalEventPage;
import com.orderpilot.api.dto.ControlInternalDtos.OperationalEventProjection;
import com.orderpilot.api.dto.ControlInternalDtos.RedisDiagnostics;
import com.orderpilot.application.services.control.ControlPlaneStatusService;
import com.orderpilot.application.services.control.OperationalEventReadService;
import com.orderpilot.application.services.control.OperationalEventRecorder;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.ApiPermissionGuard;
import com.orderpilot.security.ApiPermissionInterceptor;
import com.orderpilot.security.ApiRouteSecurityPolicy;
import com.orderpilot.security.ApiSecurityWebConfig;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * P1-E HTTP-level security proof for the bounded platform control-plane read surface through the real
 * security stack. The routes are never public; tenant, customer, ordinary service-account, and wrong
 * staff/control grants are denied before the mocked service is reached. Diagnostics require a stronger
 * permission than status reads; the operational-event read requires its own dedicated
 * STAFF_CONTROL_OPERATIONAL_EVENT_READ (never STAFF_CONTROL_READ/DIAGNOSE). Route authority is
 * single-sourced from {@link ApiRouteSecurityPolicy}: only GET/HEAD control routes carry a control
 * permission, so write-shaped methods are unmapped and fail closed with a native 405 (unauthenticated
 * callers get 401) without ever reaching the control service.
 */
@WebMvcTest(controllers = InternalControlController.class)
@Import({
    CoreConfiguration.class,
    GlobalExceptionHandler.class,
    ApiSecurityWebConfig.class,
    ApiRouteSecurityPolicy.class,
    ApiPermissionInterceptor.class,
    ApiPermissionGuard.class
})
@TestPropertySource(properties = "orderpilot.security.gateway-header-auth.enabled=true")
class InternalControlControllerSecurityTest {
  private static final String STATUS = "/api/v1/internal/control/status";
  private static final String HEALTH = "/api/v1/internal/control/health";
  private static final String READINESS = "/api/v1/internal/control/readiness";
  private static final String DIAGNOSTICS = "/api/v1/internal/control/diagnostics";
  private static final String EVENTS = "/api/v1/internal/control/operational-events";
  private static final String READ_PERMISSION = "STAFF_CONTROL_READ";
  private static final String DIAGNOSE_PERMISSION = "STAFF_CONTROL_DIAGNOSE";
  private static final String EVENT_READ_PERMISSION = "STAFF_CONTROL_OPERATIONAL_EVENT_READ";

  @Autowired private MockMvc mockMvc;

  @MockBean private ControlPlaneStatusService statusService;
  @MockBean private OperationalEventReadService eventReadService;
  @MockBean private OperationalEventRecorder eventRecorder;

  @ParameterizedTest
  @MethodSource("getCallerMatrix")
  void getControlRoutesHaveExactCallerMatrix(ControlRoute route, Caller caller, int expectedStatus)
      throws Exception {
    reset(statusService, eventReadService, eventRecorder);
    if (expectedStatus == 200) {
      stubSuccess(route);
    }

    ResultActions result = mockMvc.perform(withCaller(request(HttpMethod.GET, route.path()), caller));

    if (expectedStatus == 200) {
      result.andExpect(status().isOk());
      assertSuccessBody(result, route);
    } else if (expectedStatus == 401) {
      result.andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
          .andExpect(jsonPath("$.message").value("Authentication required"));
      verifyNoInteractions(statusService, eventReadService);
    } else {
      result.andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("TENANT_POLICY_DENIED"));
      verifyNoInteractions(statusService, eventReadService);
    }
  }

  @ParameterizedTest
  @MethodSource("methodCallerMatrix")
  void controlRoutesHaveExactCallerByMethodMatrix(
      ControlRoute route,
      HttpMethod method,
      Caller caller,
      int expectedStatus,
      String expectedCode,
      String expectedAllowHeader) throws Exception {
    reset(statusService, eventReadService, eventRecorder);
    if (expectedStatus == 200 && HttpMethod.GET.equals(method)) {
      stubSuccess(route);
    }

    ResultActions result = mockMvc.perform(withCaller(request(method, route.path()), caller));

    result.andExpect(status().is(expectedStatus));
    if (expectedCode != null) {
      result.andExpect(jsonPath("$.code").value(expectedCode));
    }
    if ("METHOD_NOT_ALLOWED".equals(expectedCode)) {
      result.andExpect(jsonPath("$.message").value("HTTP method is not supported for this API route"));
    }
    if (expectedAllowHeader != null) {
      assertAllowHeader(result, expectedAllowHeader);
    } else {
      result.andExpect(header().doesNotExist(HttpHeaders.ALLOW));
    }
    if (expectedStatus == 200 && HttpMethod.GET.equals(method)) {
      assertSuccessBody(result, route);
    } else {
      verifyNoInteractions(statusService, eventReadService);
    }
  }

  @Test
  void unknownControlSubRouteHitsDefaultDeny() throws Exception {
    mockMvc.perform(get("/api/v1/internal/control/shutdown")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, READ_PERMISSION + "," + DIAGNOSE_PERMISSION))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_POLICY_DENIED"));
    verifyNoInteractions(statusService, eventReadService);
  }

  @Test
  void trailingSlashControlRouteHitsDefaultDeny() throws Exception {
    mockMvc.perform(get(STATUS + "/").header(ApiPermissionGuard.PERMISSIONS_HEADER, READ_PERMISSION))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_POLICY_DENIED"));
    verifyNoInteractions(statusService, eventReadService);
  }

  @Test
  void unknownOrDuplicateOperationalEventQueryParameterFailsClosed() throws Exception {
    // Authorized caller, but an unknown parameter (and a duplicate) are rejected with a bounded 400
    // and the read service is never invoked.
    mockMvc.perform(get(EVENTS + "?logger=com.orderpilot.Foo")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, EVENT_READ_PERMISSION))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get(EVENTS + "?severity=INFO&severity=WARN")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, EVENT_READ_PERMISSION))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(eventReadService);
  }

  private static Stream<Arguments> getCallerMatrix() {
    return controlRoutes().flatMap(route -> callers().map(caller -> Arguments.of(
        route,
        caller,
        expectedStatus(route, HttpMethod.GET, caller))));
  }

  private static Stream<Arguments> methodCallerMatrix() {
    return controlRoutes().flatMap(route -> methods().flatMap(method -> callers().map(caller -> {
      int status = expectedStatus(route, method, caller);
      return Arguments.of(
          route,
          method,
          caller,
          status,
          expectedCode(method, caller, status),
          expectedAllowHeader(method, status));
    })));
  }

  private static Stream<ControlRoute> controlRoutes() {
    return Stream.of(
        new ControlRoute(STATUS, READ_PERMISSION, RouteKind.STATUS),
        new ControlRoute(HEALTH, READ_PERMISSION, RouteKind.HEALTH),
        new ControlRoute(READINESS, READ_PERMISSION, RouteKind.READINESS),
        new ControlRoute(DIAGNOSTICS, DIAGNOSE_PERMISSION, RouteKind.DIAGNOSTICS),
        new ControlRoute(EVENTS, EVENT_READ_PERMISSION, RouteKind.EVENTS));
  }

  private static Stream<Caller> callers() {
    return Stream.of(
        new Caller("anonymous", null),
        new Caller("tenant operator", "REVIEW_ACTION"),
        new Caller("tenant admin", "ADMIN_SETTINGS_MANAGE"),
        new Caller("external customer", "EXTERNAL_CUSTOMER_ACCESS"),
        new Caller("ordinary service account", "AI_RESULT_INTAKE"),
        new Caller("wrong staff permission", "STAFF_SUPPORT_READ"),
        new Caller("valid STAFF_CONTROL_READ", READ_PERMISSION),
        new Caller("valid STAFF_CONTROL_DIAGNOSE", DIAGNOSE_PERMISSION),
        new Caller("valid STAFF_CONTROL_OPERATIONAL_EVENT_READ", EVENT_READ_PERMISSION));
  }

  private static Stream<HttpMethod> methods() {
    return Stream.of(
        HttpMethod.GET,
        HttpMethod.HEAD,
        HttpMethod.POST,
        HttpMethod.PUT,
        HttpMethod.PATCH,
        HttpMethod.DELETE,
        HttpMethod.OPTIONS,
        HttpMethod.TRACE);
  }

  private static int expectedStatus(ControlRoute route, HttpMethod method, Caller caller) {
    if (HttpMethod.TRACE.equals(method)) {
      return 400;
    }
    if (HttpMethod.OPTIONS.equals(method)) {
      return 200;
    }
    if (caller.permissions() == null) {
      return 401;
    }
    if (HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method)) {
      return route.requiredPermission().equals(caller.permissions()) ? 200 : 403;
    }
    // Write-shaped control methods are unmapped. Route authority is single-sourced from
    // ApiRouteSecurityPolicy (GET/HEAD only), so an authenticated caller reaches the dispatcher and
    // gets a native 405 fail-closed; the control service is never invoked, regardless of permission.
    return 405;
  }

  private static String expectedCode(HttpMethod method, Caller caller, int status) {
    if (HttpMethod.OPTIONS.equals(method)) {
      return null;
    }
    if (HttpMethod.HEAD.equals(method)) {
      return null;
    }
    if (status == 401) {
      return "AUTHENTICATION_REQUIRED";
    }
    if (status == 403) {
      return "TENANT_POLICY_DENIED";
    }
    if (status == 405) {
      return "METHOD_NOT_ALLOWED";
    }
    return null;
  }

  private static String expectedAllowHeader(HttpMethod method, int status) {
    if (HttpMethod.OPTIONS.equals(method)) {
      return "GET,HEAD,OPTIONS";
    }
    if (status == 405) {
      return "GET,HEAD";
    }
    return null;
  }

  private static void assertAllowHeader(ResultActions result, String expectedAllowHeader) throws Exception {
    String actual = result.andReturn().getResponse().getHeader(HttpHeaders.ALLOW);
    assertThat(actual).isNotBlank();
    assertThat(Arrays.stream(actual.split(",")).map(String::trim).toList())
        .containsExactlyInAnyOrder(expectedAllowHeader.split(","));
  }

  private void stubSuccess(ControlRoute route) {
    switch (route.kind()) {
      case STATUS -> when(statusService.status()).thenReturn(new ControlStatusResponse(
          "unknown", 12L, List.of(new DependencyStatus("database", DependencyState.UP))));
      case HEALTH -> when(statusService.health()).thenReturn(new ControlHealthResponse("UP"));
      case READINESS -> when(statusService.readiness()).thenReturn(new ControlReadinessResponse(
          false, List.of(new DependencyStatus("database", DependencyState.DOWN))));
      case DIAGNOSTICS -> when(statusService.diagnostics()).thenReturn(new ControlDiagnosticsResponse(
          "unknown",
          List.of("test"),
          new DatabaseDiagnostics(DependencyState.UP, "65"),
          new RedisDiagnostics(false, DependencyState.NOT_CONFIGURED),
          new JvmDiagnostics(12L, 512L)));
      case EVENTS -> when(eventReadService.read(any(), any(), any(), any(), any()))
          .thenReturn(new OperationalEventPage(
              List.of(new OperationalEventProjection(
                  "2026-07-19T10:00:00Z", "DEPENDENCY_STATE_CHANGED", "DATABASE", "ERROR",
                  "dependency DATABASE state changed to DOWN", null)),
              null,
              false,
              1,
              100,
              "LOCAL_PROCESS_RECENT_OPERATIONAL_EVENTS",
              "11111111-1111-1111-1111-111111111111"));
    }
  }

  private static MockHttpServletRequestBuilder withCaller(
      MockHttpServletRequestBuilder builder,
      Caller caller) {
    if (caller.permissions() != null) {
      return builder.header(ApiPermissionGuard.PERMISSIONS_HEADER, caller.permissions());
    }
    return builder;
  }

  private static void assertSuccessBody(ResultActions result, ControlRoute route) throws Exception {
    switch (route.kind()) {
      case STATUS -> result
          .andExpect(jsonPath("$.version").value("unknown"))
          .andExpect(jsonPath("$.dependencies[0].name").value("database"))
          .andExpect(jsonPath("$.dependencies[0].state").value("UP"));
      case HEALTH -> result.andExpect(jsonPath("$.status").value("UP"));
      case READINESS -> result
          .andExpect(jsonPath("$.ready").value(false))
          .andExpect(jsonPath("$.dependencies[0].state").value("DOWN"));
      case DIAGNOSTICS -> result
          .andExpect(jsonPath("$.version").value("unknown"))
          .andExpect(jsonPath("$.database.state").value("UP"))
          .andExpect(jsonPath("$.database.migrationVersion").value("65"));
      case EVENTS -> result
          .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
          .andExpect(jsonPath("$.maxLimit").value(100))
          .andExpect(jsonPath("$.returned").value(1))
          .andExpect(jsonPath("$.scope").value("LOCAL_PROCESS_RECENT_OPERATIONAL_EVENTS"))
          .andExpect(jsonPath("$.events[0].eventCode").value("DEPENDENCY_STATE_CHANGED"))
          .andExpect(jsonPath("$.events[0].component").value("DATABASE"))
          .andExpect(jsonPath("$.events[0].severity").value("ERROR"));
    }
  }

  private record ControlRoute(String path, String requiredPermission, RouteKind kind) {}

  private record Caller(String label, String permissions) {}

  private enum RouteKind {
    STATUS,
    HEALTH,
    READINESS,
    DIAGNOSTICS,
    EVENTS
  }
}
