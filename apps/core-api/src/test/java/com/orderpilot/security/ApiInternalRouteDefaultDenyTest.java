package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.orderpilot.security.policy.TenantPolicyException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiInternalRouteDefaultDenyTest {
  private final ApiRouteSecurityPolicy policy = new ApiRouteSecurityPolicy();

  @ParameterizedTest
  @MethodSource("unknownInternalRoutes")
  void unknownOrWrongMethodInternalRoutesRemainUnclassified(String method, String path) {
    assertThat(policy.classify(method, path)).isEmpty();
  }

  @Test
  void interceptorDeniesUnknownInternalRouteBeforeAnyPermissionCheck() {
    ApiPermissionGuard guard = mock(ApiPermissionGuard.class);
    ApiPermissionInterceptor interceptor = new ApiPermissionInterceptor(guard, policy);
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/internal/support/unknown-maintenance-action");

    assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("Unclassified API route GET");
    verifyNoInteractions(guard);
  }

  private static Stream<org.junit.jupiter.params.provider.Arguments> unknownInternalRoutes() {
    String diagnostics =
        "/api/v1/internal/support/tenants/123e4567-e89b-12d3-a456-426614174000/diagnostics";
    return Stream.of(
        route("GET", "/api/v1/internal/not-a-real-route"),
        route("GET", "/api/v1/internal/support/unknown-maintenance-action"),
        route("POST", diagnostics),
        route("DELETE", "/api/v1/internal/support/incidents/123e4567-e89b-12d3-a456-426614174111"),
        route("GET", "/api/v1/internal/supporting/diagnostics"),
        // P1-E: the control surface is GET-only and explicitly enumerated — write-shaped and unknown
        // control routes stay unclassified and hit the global default-deny.
        route("POST", "/api/v1/internal/control/status"),
        route("POST", "/api/v1/internal/control/diagnostics"),
        route("DELETE", "/api/v1/internal/control/status"),
        route("GET", "/api/v1/internal/control/shutdown"),
        route("GET", "/api/v1/internal/control/status/extra"),
        // P1-E lifecycle (operational-event slice): the operational-event read is GET/HEAD only and
        // matched exactly; write-shaped and deeper sub-paths stay unclassified and hit default-deny.
        route("POST", "/api/v1/internal/control/operational-events"),
        route("DELETE", "/api/v1/internal/control/operational-events"),
        route("GET", "/api/v1/internal/control/operational-events/extra"));
  }

  private static org.junit.jupiter.params.provider.Arguments route(String method, String path) {
    return org.junit.jupiter.params.provider.Arguments.of(method, path);
  }
}
