package com.orderpilot.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-43C — gateway-header-auth disabled mode.
 *
 * <p>The production default is {@code gateway-header-auth.enabled=false} (see application.yml). In that
 * mode the {@code ApiHeaderAuthenticationFilter} never trusts client-supplied authority headers, so a
 * request carrying {@code X-Tenant-Id} / {@code X-OrderPilot-Actor-Id} / {@code X-OrderPilot-Permissions}
 * stays anonymous and a protected {@code /api/**} route fails closed with 401. This is the strongest
 * statement of "client-supplied headers are not authority": even a fully-populated permission header
 * grants nothing when header trust is off.
 *
 * <p>The dev/test convenience mode ({@code enabled=true, signature-required=false}) is exercised by
 * {@link ApiPermissionRouteCoverageTest}; the production signed mode by
 * {@link ApiGatewayHeaderAuthenticationHardeningTest}.
 */
@WebMvcTest(controllers = ApiHeaderAuthenticationFilterDisabledModeTest.DisabledModeProbeController.class)
@Import({
    CoreConfiguration.class,
    GlobalExceptionHandler.class,
    ApiSecurityWebConfig.class,
    ApiRouteSecurityPolicy.class,
    ApiPermissionInterceptor.class,
    ApiPermissionGuard.class,
    ApiHeaderAuthenticationFilterDisabledModeTest.DisabledModeProbeController.class
})
@TestPropertySource(properties = "orderpilot.security.gateway-header-auth.enabled=false")
class ApiHeaderAuthenticationFilterDisabledModeTest {
  private static final String PROBE_ROUTE = "/api/stage8/analytics/stage43c-disabled-probe";

  @Autowired private MockMvc mockMvc;

  @Test
  void disabledGatewayHeaderAuthDoesNotTrustClientSuppliedAuthorityHeaders() throws Exception {
    mockMvc.perform(get(PROBE_ROUTE)
            .header(GatewayHeaderSignatureVerifier.TENANT_HEADER, "11111111-1111-1111-1111-111111111111")
            .header(RequestActorResolver.ACTOR_HEADER, "22222222-2222-2222-2222-222222222222")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, ApiPermission.ANALYTICS_READ.name()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void disabledGatewayHeaderAuthStillServesIntentionalPublicHealthRoute() throws Exception {
    // Disabled header trust must not break the explicit public allowlist proven in 43A/43B.
    mockMvc.perform(get("/api/v1/health"))
        .andExpect(status().isOk());
  }

  @RestController
  static class DisabledModeProbeController {
    @GetMapping(PROBE_ROUTE)
    Map<String, String> read() {
      return Map.of("route", "stage8-disabled-probe");
    }

    @GetMapping("/api/v1/health")
    Map<String, String> health() {
      return Map.of("status", "UP");
    }
  }
}
