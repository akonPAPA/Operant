package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.rest.HealthController;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-44E — proves the ApiPermissionInterceptor is registered on the ENTIRE /api/** surface and
 * therefore fails closed on routes outside the historically-registered /api/v1, /api/stage8,
 * /api/stage9 prefixes.
 *
 * <p>The residual gap this closes: the interceptor used to be registered on only those three prefixes.
 * A future controller mapped under a different /api group (here a representative /api/v2/... route)
 * would have been authenticated by Spring Security but never reach an authorization check — a fail-open
 * authorization hole. With /api/** registration the interceptor runs and ApiRouteSecurityPolicy denies
 * any path it does not explicitly classify (default-deny), while the narrow public set stays public.
 */
@WebMvcTest(controllers = {
    HealthController.class,
    ApiSecurityWebConfigPermissionCoverageTest.CoverageProbeController.class
})
@Import({
    CoreConfiguration.class,
    GlobalExceptionHandler.class,
    ApiSecurityWebConfig.class,
    ApiRouteSecurityPolicy.class,
    ApiPermissionInterceptor.class,
    ApiPermissionGuard.class,
    ApiSecurityWebConfigPermissionCoverageTest.CoverageProbeController.class
})
@TestPropertySource(properties = "orderpilot.security.gateway-header-auth.enabled=true")
class ApiSecurityWebConfigPermissionCoverageTest {
  private static final String AUTHENTICATED_PROBE = "AUTHENTICATED_PROBE";

  @Autowired private MockMvc mockMvc;

  @Test
  void interceptorIsRegisteredOnTheWholeApiSurfaceNotJustKnownPrefixes() {
    assertThat(Arrays.asList(ApiSecurityWebConfig.PERMISSION_INTERCEPTOR_PATHS))
        .containsExactly("/api/**");
  }

  // --- interceptor applies to the known shipped groups: /api/v1, /api/stage8, /api/stage9 ---

  @Test
  void interceptorAppliesToApiV1Routes() throws Exception {
    mockMvc.perform(get("/api/v1/analytics/coverage-probe")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, AUTHENTICATED_PROBE))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Missing required API permission ANALYTICS_READ"));

    mockMvc.perform(get("/api/v1/analytics/coverage-probe")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, ApiPermission.ANALYTICS_READ.name()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.route").value("api-v1"));
  }

  @Test
  void interceptorAppliesToStage8Routes() throws Exception {
    mockMvc.perform(get("/api/stage8/analytics/coverage-probe")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, AUTHENTICATED_PROBE))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Missing required API permission ANALYTICS_READ"));
  }

  @Test
  void interceptorAppliesToStage9Routes() throws Exception {
    mockMvc.perform(get("/api/stage9/integrations/coverage-probe")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, AUTHENTICATED_PROBE))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Missing required API permission ADMIN_SETTINGS_READ"));
  }

  // --- the residual gap: an out-of-tree /api/v2 route is now covered and fails closed ---

  @Test
  void outOfTreeApiRouteFailsClosedWhenAuthenticatedButUnclassified() throws Exception {
    // Previously /api/v2/** was outside PERMISSION_INTERCEPTOR_PATHS: an authenticated caller would
    // have reached the handler with no permission check. Now the interceptor runs and the policy has
    // no classification for it, so it is denied (default-deny / fail closed) instead of permitted.
    mockMvc.perform(get("/api/v2/future-feature/coverage-probe")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, ApiPermission.ANALYTICS_READ.name()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_POLICY_DENIED"))
        .andExpect(jsonPath("$.message").value("Unclassified API route GET /api/v2/future-feature/coverage-probe"));
  }

  @Test
  void outOfTreeApiRouteRejectsMissingAuthentication() throws Exception {
    mockMvc.perform(get("/api/v2/future-feature/coverage-probe"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  // --- the public exclusions stay narrow and remain public ---

  @Test
  void publicHealthRouteRemainsPublic() throws Exception {
    mockMvc.perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void publicProviderWebhookRemainsPublic() throws Exception {
    mockMvc.perform(post("/api/v1/webhooks/telegram/coverage-probe")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.route").value("public-webhook"));
  }

  @Test
  void publicSetIsLimitedToHealthAndProviderWebhooks() {
    // No business route may be in the public GET / POST allowlists. Both lists are small and named.
    assertThat(ApiSecurityWebConfig.PUBLIC_GET_ROUTES)
        .containsExactlyInAnyOrder("/", "/favicon.ico", "/actuator/health", "/actuator/info", "/api/v1/health");
    for (String route : ApiSecurityWebConfig.PUBLIC_POST_WEBHOOK_ROUTES) {
      assertThat(route).contains("webhook");
    }
  }

  @RestController
  static class CoverageProbeController {
    @GetMapping("/api/v1/analytics/coverage-probe")
    Map<String, String> apiV1() {
      return Map.of("route", "api-v1");
    }

    @GetMapping("/api/stage8/analytics/coverage-probe")
    Map<String, String> stage8() {
      return Map.of("route", "stage8");
    }

    @GetMapping("/api/stage9/integrations/coverage-probe")
    Map<String, String> stage9() {
      return Map.of("route", "stage9");
    }

    @GetMapping("/api/v2/future-feature/coverage-probe")
    Map<String, String> outOfTree() {
      return Map.of("route", "out-of-tree");
    }

    @PostMapping("/api/v1/webhooks/telegram/coverage-probe")
    Map<String, String> publicWebhook() {
      return Map.of("route", "public-webhook");
    }
  }
}
