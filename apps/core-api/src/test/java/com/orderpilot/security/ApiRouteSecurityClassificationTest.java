package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.OrderPilotApplication;
import com.orderpilot.security.ApiRouteSecurityPolicy.RouteDecision;
import com.orderpilot.security.ApiRouteSecurityPolicy.SecurityClassification;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@SpringBootTest(
    classes = OrderPilotApplication.class,
    properties = {
        "spring.main.lazy-initialization=true",
        "orderpilot.security.gateway-header-auth.enabled=false"
    })
@ActiveProfiles("test")
class ApiRouteSecurityClassificationTest {
  private static final Set<String> EXPECTED_PUBLIC_ROUTES = Set.of(
      "GET /api/v1/health",
      "POST /api/v1/bot/telegram/webhook",
      "POST /api/v1/bot-runtime/telegram/webhook",
      "POST /api/v1/channel-gateway/whatsapp/webhook",
      "POST /api/v1/webhooks/channels/bot/telegram/{connectionId}",
      "POST /api/v1/webhooks/channels/meta-messenger/{connectionId}",
      "POST /api/v1/webhooks/channels/telegram/{connectionId}",
      "POST /api/v1/webhooks/channels/viber/{connectionId}",
      "POST /api/v1/webhooks/channels/wechat/{connectionId}",
      "POST /api/v1/webhooks/channels/whatsapp/{connectionId}",
      "POST /api/v1/webhooks/email",
      "POST /api/v1/webhooks/telegram",
      "POST /api/v1/webhooks/telegram/{tenantKey}",
      "POST /api/v1/webhooks/whatsapp",
      "POST /api/v1/webhooks/whatsapp/{tenantKey}");

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping handlerMapping;
  @Autowired private ApiRouteSecurityPolicy policy;

  @Test
  void allRegisteredApiMvcRoutesAreClassified() {
    List<RouteMapping> routes = apiRoutes();

    List<String> unclassified = routes.stream()
        .filter(route -> policy.classify(route.method(), route.path()).isEmpty())
        .map(RouteMapping::description)
        .toList();

    long publicRoutes = routes.stream()
        .flatMap(route -> policy.classify(route.method(), route.path()).stream())
        .filter(RouteDecision::isPublic)
        .count();

    System.out.printf(
        "API route inventory: total=%d public=%d protected=%d unclassified=%d%n",
        routes.size(),
        publicRoutes,
        routes.size() - publicRoutes,
        unclassified.size());

    assertThat(routes).isNotEmpty();
    assertThat(unclassified).isEmpty();
  }

  @Test
  void publicApiRoutesAreExplicitlyAllowlisted() {
    Set<String> publicRoutes = apiRoutes().stream()
        .filter(route -> policy.classify(route.method(), route.path()).map(RouteDecision::isPublic).orElse(false))
        .map(RouteMapping::signature)
        .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));

    assertThat(publicRoutes).containsExactlyElementsOf(EXPECTED_PUBLIC_ROUTES.stream().sorted().toList());
  }

  @ParameterizedTest
  @MethodSource("unknownApiRoutes")
  void unknownApiRoutesHaveNoClassification(RouteMapping route) {
    assertThat(policy.classify(route.method(), route.path()))
        .as(route.signature())
        .isEmpty();
  }

  @ParameterizedTest
  @MethodSource("representativeProtectedRoutes")
  void representativeProtectedRoutesHaveExpectedClassification(RouteExpectation route) {
    RouteDecision decision = policy.classify(route.method(), route.path()).orElseThrow();

    assertThat(decision.classification()).isEqualTo(route.classification());
    assertThat(decision.requiredPermission()).isEqualTo(route.permission());
  }

  private List<RouteMapping> apiRoutes() {
    return handlerMapping.getHandlerMethods().entrySet().stream()
        .flatMap(entry -> routeMappings(entry.getKey(), entry.getValue().toString()))
        .filter(route -> route.path().startsWith("/api/"))
        .sorted(Comparator.comparing(RouteMapping::path).thenComparing(RouteMapping::method))
        .toList();
  }

  private static Stream<RouteMapping> routeMappings(RequestMappingInfo info, String handler) {
    Set<String> detectedMethods = info.getMethodsCondition().getMethods().stream()
        .map(Enum::name)
        .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
    Set<String> methods = detectedMethods.isEmpty()
        ? Set.of(
          HttpMethod.GET.name(),
          HttpMethod.POST.name(),
          HttpMethod.PUT.name(),
          HttpMethod.PATCH.name(),
          HttpMethod.DELETE.name())
        : detectedMethods;
    Set<String> patterns;
    if (info.getPathPatternsCondition() != null) {
      patterns = info.getPathPatternsCondition().getPatternValues();
    } else if (info.getPatternsCondition() != null) {
      patterns = info.getPatternsCondition().getPatterns();
    } else {
      patterns = Set.of();
    }
    return patterns.stream()
        .flatMap(pattern -> methods.stream().map(method -> new RouteMapping(method, pattern, handler)));
  }

  private static Stream<RouteMapping> unknownApiRoutes() {
    return Stream.of(
        new RouteMapping("GET", "/api/v1/stage40d-new-route", "unknown"),
        new RouteMapping("GET", "/api/stage8/stage40d-new-route", "unknown"),
        new RouteMapping("GET", "/api/stage9/stage40d-new-route", "unknown"));
  }

  private static Stream<RouteExpectation> representativeProtectedRoutes() {
    return Stream.of(
        new RouteExpectation(
            "GET",
            "/api/v1/analytics/overview",
            SecurityClassification.PROTECTED_READ,
            ApiPermission.ANALYTICS_READ),
        new RouteExpectation(
            "GET",
            "/api/stage8/reconciliation/summary",
            SecurityClassification.PROTECTED_READ,
            ApiPermission.ANALYTICS_READ),
        new RouteExpectation(
            "POST",
            "/api/stage8/reconciliation/refresh",
            SecurityClassification.PROTECTED_REFRESH_RECOMPUTE,
            ApiPermission.ANALYTICS_MANAGE),
        new RouteExpectation(
            "PUT",
            "/api/stage8/value/roi-assumptions",
            SecurityClassification.PROTECTED_ADMIN_CONFIG,
            ApiPermission.ANALYTICS_MANAGE),
        new RouteExpectation(
            "POST",
            "/api/stage9/integrations/demo-erp",
            SecurityClassification.PROTECTED_ADMIN_CONFIG,
            ApiPermission.ADMIN_SETTINGS_MANAGE),
        new RouteExpectation(
            "GET",
            "/api/stage9/change-requests/123",
            SecurityClassification.PROTECTED_READ,
            ApiPermission.CHANGE_REQUEST_READ),
        new RouteExpectation(
            "POST",
            "/api/stage9/change-requests/123/approve",
            SecurityClassification.PROTECTED_APPROVE,
            ApiPermission.CHANGE_REQUEST_APPROVE),
        new RouteExpectation(
            "POST",
            "/api/stage9/change-requests/123/reject",
            SecurityClassification.PROTECTED_REJECT,
            ApiPermission.CHANGE_REQUEST_REJECT),
        new RouteExpectation(
            "POST",
            "/api/stage9/change-requests/123/execute",
            SecurityClassification.PROTECTED_EXECUTE,
            ApiPermission.CHANGE_REQUEST_EXECUTE),
        new RouteExpectation(
            "POST",
            "/api/v1/runtime/plans",
            SecurityClassification.PROTECTED_RUNTIME_MANAGE,
            ApiPermission.RUNTIME_ENTITLEMENT_MANAGE));
  }

  private record RouteMapping(String method, String path, String handler) {
    private String signature() {
      return method + " " + path;
    }

    private String description() {
      return signature() + " -> " + handler;
    }
  }

  private record RouteExpectation(
      String method,
      String path,
      SecurityClassification classification,
      ApiPermission permission) {}
}
