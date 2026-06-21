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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = {
    HealthController.class,
    ApiPermissionRouteCoverageTest.RouteMatrixProbeController.class
})
@Import({
    CoreConfiguration.class,
    GlobalExceptionHandler.class,
    ApiSecurityWebConfig.class,
    ApiPermissionInterceptor.class,
    ApiPermissionGuard.class,
    ApiPermissionRouteCoverageTest.RouteMatrixProbeController.class
})
@TestPropertySource(properties = "orderpilot.security.gateway-header-auth.enabled=true")
class ApiPermissionRouteCoverageTest {
  private static final String AUTHENTICATED_PROBE = "AUTHENTICATED_PROBE";
  private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

  @Autowired private MockMvc mockMvc;
  @Autowired private ApiPermissionInterceptor interceptor;

  @ParameterizedTest
  @MethodSource("protectedRoutes")
  void protectedRouteGroupsRejectAuthenticatedRequestWithoutRequiredPermission(RouteExpectation route) throws Exception {
    mockMvc.perform(route.request().header(ApiPermissionGuard.PERMISSIONS_HEADER, AUTHENTICATED_PROBE))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_POLICY_DENIED"))
        .andExpect(jsonPath("$.message").value("Missing required API permission " + route.requiredPermissionName()));
  }

  @ParameterizedTest
  @MethodSource("protectedRoutes")
  void protectedRouteGroupsAllowAuthenticatedRequestWithRequiredPermission(RouteExpectation route) throws Exception {
    mockMvc.perform(route.request().header(ApiPermissionGuard.PERMISSIONS_HEADER, route.requiredPermissionName()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.route").value(route.name()));
  }

  @ParameterizedTest
  @MethodSource("protectedRoutes")
  void routeMatrixProtectedRoutesHaveExplicitPermissionRules(RouteExpectation route) {
    assertThat(interceptor.requiredPermissionFor(route.method().name(), route.path()))
        .as("%s is %s and must resolve through the real permission mapper", route.name(), route.classification())
        .isEqualTo(route.requiredPermission());
  }

  @Test
  void missingAuthenticationOnProtectedRouteReturns401() throws Exception {
    mockMvc.perform(get("/api/v1/analytics/stage40b-route-matrix"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void publicHealthRouteRemainsIntentionallyPublic() throws Exception {
    mockMvc.perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void publicProviderWebhookRouteRemainsExplicitlyPublic() throws Exception {
    mockMvc.perform(post("/api/v1/webhooks/telegram/stage40b")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.classification").value("WEBHOOK_PUBLIC_WITH_SIGNATURE_OR_TOKEN"));
  }

  @Test
  void publicBotRuntimeWebhookRouteRemainsExplicitlyPublic() throws Exception {
    mockMvc.perform(post("/api/v1/bot-runtime/telegram/webhook")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.classification").value("WEBHOOK_PUBLIC_WITH_SIGNATURE_OR_TOKEN"));
  }

  @Test
  void publicWebhookRoutesHaveNoPermissionRuleBecauseTheyAreProviderFacing() {
    assertThat(interceptor.requiredPermissionFor("POST", "/api/v1/webhooks/telegram/stage40b")).isNull();
    assertThat(interceptor.requiredPermissionFor("POST", "/api/v1/bot-runtime/telegram/webhook")).isNull();
  }

  @Test
  void unknownApiProbeRouteIsNotPublicWithoutAuthentication() throws Exception {
    mockMvc.perform(get("/api/stage8/stage40b-unknown-probe"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void interceptorRegistrationCoversAllRouteMatrixProtectedGroups() {
    assertThat(Arrays.asList(ApiSecurityWebConfig.PERMISSION_INTERCEPTOR_PATHS))
        .contains("/api/v1/**", "/api/stage8/**", "/api/stage9/**");
    protectedRoutes().forEach(route ->
        assertThat(matchesAny(route.path(), ApiSecurityWebConfig.PERMISSION_INTERCEPTOR_PATHS))
            .as("%s must be under an ApiPermissionInterceptor path", route.name())
            .isTrue());
  }

  private static Stream<RouteExpectation> protectedRoutes() {
    return Stream.of(
        new RouteExpectation(
            "api-v1-business",
            HttpMethod.GET,
            "/api/v1/analytics/stage40b-route-matrix",
            "PERMISSION_REQUIRED",
            ApiPermission.ANALYTICS_READ),
        new RouteExpectation(
            "stage8-business",
            HttpMethod.GET,
            "/api/stage8/analytics/stage40b-route-matrix",
            "PERMISSION_REQUIRED",
            ApiPermission.ANALYTICS_READ),
        new RouteExpectation(
            "stage9-integration-admin",
            HttpMethod.GET,
            "/api/stage9/integrations/stage40b-route-matrix",
            "PERMISSION_REQUIRED",
            ApiPermission.ADMIN_SETTINGS_READ),
        new RouteExpectation(
            "api-v1-integration-admin",
            HttpMethod.GET,
            "/api/v1/integrations/stage40b-route-matrix",
            "PERMISSION_REQUIRED",
            ApiPermission.ADMIN_SETTINGS_READ),
        new RouteExpectation(
            "stage9-change-request",
            HttpMethod.GET,
            "/api/stage9/change-requests/stage40b-route-matrix",
            "PERMISSION_REQUIRED",
            ApiPermission.CHANGE_REQUEST_READ),
        new RouteExpectation(
            "runtime-admin-mutation",
            HttpMethod.POST,
            "/api/v1/runtime/stage40b-route-matrix",
            "PERMISSION_REQUIRED",
            ApiPermission.RUNTIME_ENTITLEMENT_MANAGE));
  }

  private record RouteExpectation(
      String name,
      HttpMethod method,
      String path,
      String classification,
      ApiPermission requiredPermission) {
    MockHttpServletRequestBuilder request() {
      MockHttpServletRequestBuilder builder = HttpMethod.POST.equals(method) ? post(path) : get(path);
      if (HttpMethod.POST.equals(method)) {
        builder.contentType(MediaType.APPLICATION_JSON).content("{}");
      }
      return builder;
    }

    String requiredPermissionName() {
      return requiredPermission.name();
    }
  }

  private static boolean matchesAny(String path, String[] patterns) {
    for (String pattern : patterns) {
      if (PATH_MATCHER.match(pattern, path)) {
        return true;
      }
    }
    return false;
  }

  @RestController
  static class RouteMatrixProbeController {
    @GetMapping("/api/v1/analytics/stage40b-route-matrix")
    Map<String, String> apiV1Business() {
      return Map.of("route", "api-v1-business");
    }

    @GetMapping("/api/stage8/analytics/stage40b-route-matrix")
    Map<String, String> stage8Business() {
      return Map.of("route", "stage8-business");
    }

    @GetMapping("/api/stage9/integrations/stage40b-route-matrix")
    Map<String, String> stage9IntegrationAdmin() {
      return Map.of("route", "stage9-integration-admin");
    }

    @GetMapping("/api/v1/integrations/stage40b-route-matrix")
    Map<String, String> apiV1IntegrationAdmin() {
      return Map.of("route", "api-v1-integration-admin");
    }

    @GetMapping("/api/stage9/change-requests/stage40b-route-matrix")
    Map<String, String> stage9ChangeRequest() {
      return Map.of("route", "stage9-change-request");
    }

    @PostMapping("/api/v1/runtime/stage40b-route-matrix")
    Map<String, String> runtimeAdminMutation() {
      return Map.of("route", "runtime-admin-mutation");
    }

    @PostMapping("/api/v1/webhooks/telegram/stage40b")
    Map<String, String> publicWebhook() {
      return Map.of("classification", "WEBHOOK_PUBLIC_WITH_SIGNATURE_OR_TOKEN");
    }

    @PostMapping("/api/v1/bot-runtime/telegram/webhook")
    Map<String, String> publicBotRuntimeWebhook() {
      return Map.of("classification", "WEBHOOK_PUBLIC_WITH_SIGNATURE_OR_TOKEN");
    }
  }
}
