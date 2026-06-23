package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.OrderPilotApplication;
import jakarta.servlet.Filter;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.HandlerMapping;

/**
 * OP-CAP-43B — Non-MVC API surface + public allowlist justification guard.
 *
 * <p>OP-CAP-43A ({@link ApiRouteSecurityClassificationTest}) proved that every route registered with
 * Spring MVC {@code RequestMappingHandlerMapping} under {@code /api/**} is classified by
 * {@link ApiRouteSecurityPolicy} and that public MVC routes match an explicit allowlist. That proof
 * only enumerates MVC handler methods. It cannot see HTTP surfaces registered <em>outside</em> the
 * MVC handler mapping: raw {@code Filter}/{@code Servlet} registrations, custom {@code HandlerMapping}
 * beans, {@code WebSecurityCustomizer} {@code web.ignoring()} holes, actuator exposure, or a
 * security-chain {@code permitAll()} entry that has no corresponding controller.
 *
 * <p>This test pins that residual boundary: it proves no such surface silently bypasses the
 * permission / default-deny model, and that the security-chain public allowlist is the single,
 * justified source of public exposure and cannot grow without a visible test diff. It is intentionally
 * test-only; no production behaviour is changed by this stage.
 */
@SpringBootTest(
    classes = OrderPilotApplication.class,
    properties = {
        "spring.main.lazy-initialization=true",
        "orderpilot.security.gateway-header-auth.enabled=false"
    })
@ActiveProfiles("test")
class ApiNonMvcSecuritySurfaceCoverageTest {

  private static final String APP_PACKAGE = "com.orderpilot";

  // Enforcement-layer public allowlist mirror. Keys must equal ApiSecurityWebConfig.PUBLIC_GET_ROUTES
  // exactly; each value is the justification (reason / owner / security rationale). This duplication is
  // deliberate: the security chain permitAll() arrays cannot grow without a matching, justified entry
  // here, which surfaces as a clear test diff during review.
  private static final Map<String, String> EXPECTED_PUBLIC_GET = Map.ofEntries(
      Map.entry("/",
          "root: deliberately public, non-sensitive landing path; not an /api surface"),
      Map.entry("/favicon.ico",
          "favicon: deliberately public static asset; not an /api surface"),
      Map.entry("/actuator/health",
          "actuator health: liveness/readiness probe; actuator exposure limited to health,info"),
      Map.entry("/actuator/info",
          "actuator info: build/info probe; actuator exposure limited to health,info"),
      Map.entry("/api/v1/health",
          "MVC health endpoint: intentional public liveness check, no tenant/business data"));

  // Keys must equal ApiSecurityWebConfig.PUBLIC_POST_WEBHOOK_ROUTES exactly. Every entry is a
  // provider-facing inbound webhook whose authenticity is enforced by signature / raw-body / tenant-key
  // verification inside the controller, not by the permission interceptor.
  private static final Map<String, String> EXPECTED_PUBLIC_POST = Map.ofEntries(
      Map.entry("/api/v1/bot/telegram/webhook",
          "Telegram bot webhook: provider-facing, verified by bot token/secret in controller"),
      Map.entry("/api/v1/bot-runtime/telegram/webhook",
          "Telegram bot-runtime webhook: provider-facing, verified by bot token/secret in controller"),
      Map.entry("/api/v1/webhooks/email",
          "Email intake webhook: provider-facing, verified by intake contract in controller"),
      Map.entry("/api/v1/webhooks/telegram",
          "Telegram channel webhook: provider-facing, verified in controller"),
      Map.entry("/api/v1/webhooks/telegram/*",
          "Telegram channel webhook (tenant key): provider-facing, tenant-key verified in controller"),
      Map.entry("/api/v1/webhooks/whatsapp",
          "WhatsApp channel webhook: provider-facing, verified in controller"),
      Map.entry("/api/v1/webhooks/whatsapp/*",
          "WhatsApp channel webhook (tenant key): provider-facing, tenant-key verified in controller"),
      Map.entry("/api/v1/webhooks/channels/bot/telegram/*",
          "Telegram channel-bot webhook (connection): provider-facing, verified in controller"),
      Map.entry("/api/v1/webhooks/channels/telegram/*",
          "Telegram channel webhook (connection): provider-facing, verified in controller"),
      Map.entry("/api/v1/webhooks/channels/whatsapp/*",
          "WhatsApp channel webhook (connection): provider-facing, verified in controller"),
      Map.entry("/api/v1/webhooks/channels/meta-messenger/*",
          "Meta Messenger webhook (connection): provider-facing, byte-exact raw-body HMAC verified"),
      Map.entry("/api/v1/webhooks/channels/viber/*",
          "Viber channel webhook (connection): provider-facing, verified in controller"),
      Map.entry("/api/v1/webhooks/channels/wechat/*",
          "WeChat channel webhook (connection): provider-facing, verified in controller"),
      Map.entry("/api/v1/channel-gateway/whatsapp/webhook",
          "WhatsApp channel-gateway webhook: provider-facing, verified in controller"));

  // No public route may fall under these protected/sensitive families. The webhook + health allowlist
  // above is deliberately disjoint from every one of these prefixes.
  private static final List<String> FORBIDDEN_PUBLIC_PREFIXES = List.of(
      "/api/v1/admin",
      "/api/v1/internal",
      "/api/v1/debug",
      "/api/v1/change-requests",
      "/api/stage9/change-requests",
      "/api/v1/reconciliation",
      "/api/stage8/reconciliation",
      "/api/stage9/integrations",
      "/api/v1/runtime",
      "/api/stage8/",
      "/api/stage9/");

  @Autowired private ApplicationContext context;
  @Autowired private ApiRouteSecurityPolicy policy;

  // 1. A WebSecurityCustomizer.ignoring() match removes a path from the Spring Security filter chain
  // entirely (no authentication, no authorization). None may exist — if any did, an /api route could be
  // served with zero permission enforcement and would be invisible to the MVC classification test.
  @Test
  void webSecurityCustomizerDoesNotIgnoreProtectedApiRoutes() {
    Map<String, WebSecurityCustomizer> customizers = context.getBeansOfType(WebSecurityCustomizer.class);

    assertThat(customizers)
        .as("No WebSecurityCustomizer may exist: web.ignoring() bypasses the filter chain and could "
            + "expose an unauthenticated /api surface outside the classification model")
        .isEmpty();
  }

  // 2. Raw filter/servlet registrations are the classic way to attach an HTTP surface beside the
  // DispatcherServlet. No application-owned registration bean may exist.
  @Test
  void noApplicationFilterOrServletRegistrationExposesApiSurface() {
    List<String> appFilterRegistrations =
        applicationOwnedRegistrations(context.getBeansOfType(FilterRegistrationBean.class));
    List<String> appServletRegistrations =
        applicationOwnedRegistrations(context.getBeansOfType(ServletRegistrationBean.class));

    assertThat(appFilterRegistrations)
        .as("No application FilterRegistrationBean may register an ad-hoc HTTP surface outside the "
            + "security chain")
        .isEmpty();
    assertThat(appServletRegistrations)
        .as("No application ServletRegistrationBean may expose an HTTP endpoint outside the "
            + "DispatcherServlet / security chain")
        .isEmpty();
  }

  // 3. The only application-defined servlet Filter bean is the tenant-context filter, which sets/clears
  // TenantContext and makes no allow/deny decision. Any new raw filter would appear here and force a
  // review. (ApiHeaderAuthenticationFilter is an inner class added via HttpSecurity.addFilterBefore,
  // not a bean, so it is part of the chain rather than an independent surface.)
  @Test
  void applicationFilterBeansAreExactlyTheKnownTenantContextFilter() {
    List<String> appFilters = context.getBeansOfType(Filter.class).values().stream()
        .map(filter -> filter.getClass().getName())
        .filter(name -> name.startsWith(APP_PACKAGE))
        .sorted()
        .toList();

    assertThat(appFilters)
        .as("Only the context-only TenantContextFilter may be an application Filter bean")
        .containsExactly("com.orderpilot.common.tenant.TenantContextFilter");
  }

  // 4. No application-owned custom HandlerMapping (e.g. RouterFunctionMapping, SimpleUrlHandlerMapping)
  // may exist. Such a bean could route /api/** to handlers the MVC RequestMappingHandlerMapping never
  // sees, defeating the 43A classification enumeration.
  @Test
  void noApplicationCustomHandlerMappingIntroducesUnclassifiedApiSurface() {
    List<String> appHandlerMappings = context.getBeansOfType(HandlerMapping.class).values().stream()
        .map(mapping -> mapping.getClass().getName())
        .filter(name -> name.startsWith(APP_PACKAGE))
        .sorted()
        .toList();

    assertThat(appHandlerMappings)
        .as("No application-owned custom HandlerMapping may route /api outside the MVC handler mapping")
        .isEmpty();
  }

  // 5. There is exactly one application SecurityFilterChain and the enforcement-layer public allowlist
  // arrays equal the explicit, justified expected sets. This is the guard that EXPECTED_PUBLIC_ROUTES
  // cannot grow silently: any new permitAll() entry must be added here with a reason.
  @Test
  void publicRoutesRemainExplicitlyJustifiedAndCannotGrowSilently() {
    assertThat(context.getBeansOfType(SecurityFilterChain.class))
        .as("Exactly one application SecurityFilterChain is expected")
        .hasSize(1);

    assertThat(toSortedSignature(ApiSecurityWebConfig.PUBLIC_GET_ROUTES))
        .as("Security-chain public GET allowlist must equal the justified expected set")
        .isEqualTo(toSortedSignature(EXPECTED_PUBLIC_GET.keySet().toArray(String[]::new)));

    assertThat(toSortedSignature(ApiSecurityWebConfig.PUBLIC_POST_WEBHOOK_ROUTES))
        .as("Security-chain public POST webhook allowlist must equal the justified expected set")
        .isEqualTo(toSortedSignature(EXPECTED_PUBLIC_POST.keySet().toArray(String[]::new)));

    EXPECTED_PUBLIC_GET.forEach((path, reason) ->
        assertThat(reason).as("public GET %s must carry a justification", path).isNotBlank());
    EXPECTED_PUBLIC_POST.forEach((path, reason) ->
        assertThat(reason).as("public POST %s must carry a justification", path).isNotBlank());
  }

  // 6. The enforcement allowlist (security chain permitAll) and the classifier (ApiRouteSecurityPolicy)
  // must agree: every /api public chain entry classifies as public, and no public entry falls under a
  // forbidden protected family. Non-/api entries (root, favicon, actuator) are outside policy scope.
  @Test
  void noApiSurfaceBypassesMvcRouteClassificationOrExplicitPublicAllowlist() {
    for (String getRoute : ApiSecurityWebConfig.PUBLIC_GET_ROUTES) {
      assertNotForbidden(getRoute);
      if (getRoute.startsWith("/api/")) {
        assertThat(policy.classify("GET", getRoute).map(ApiRouteSecurityPolicy.RouteDecision::isPublic))
            .as("public GET /api route %s must be classified public by the policy", getRoute)
            .contains(true);
      } else {
        assertThat(policy.classify("GET", getRoute))
            .as("non-/api public route %s is outside policy scope", getRoute)
            .isEmpty();
      }
    }

    for (String postRoute : ApiSecurityWebConfig.PUBLIC_POST_WEBHOOK_ROUTES) {
      assertNotForbidden(postRoute);
      String concrete = postRoute.replace("*", "sample-segment");
      assertThat(policy.classify("POST", concrete).map(ApiRouteSecurityPolicy.RouteDecision::isPublic))
          .as("public POST webhook route %s must be classified public by the policy", postRoute)
          .contains(true);
    }
  }

  // 7. Actuator is a registered HTTP surface but not part of /api. Production exposure (read from the
  // main application.yml, since the test profile's application.yml shadows it on the classpath) must
  // stay limited to a non-wildcard subset of health,info; only those two actuator paths are public, and
  // everything else under /actuator is denied by the security chain
  // (.requestMatchers("/actuator/**").denyAll()). No actuator path is /api.
  @Test
  void actuatorExposureDoesNotCreateApiBypass() throws IOException {
    List<String> declaredExposures = productionActuatorWebExposureInclude();
    assertThat(declaredExposures)
        .as("Production application.yml must declare a bounded actuator web exposure (health,info)")
        .isNotEmpty();
    assertThat(declaredExposures)
        .as("Actuator web exposure must stay within health,info and never use a wildcard")
        .allSatisfy(value -> assertThat(splitCsvLower(value))
            .isSubsetOf("health", "info"));

    List<String> publicActuatorRoutes = Arrays.stream(ApiSecurityWebConfig.PUBLIC_GET_ROUTES)
        .filter(route -> route.startsWith("/actuator"))
        .sorted()
        .toList();
    assertThat(publicActuatorRoutes)
        .as("Only the health,info actuator endpoints may be public")
        .containsExactly("/actuator/health", "/actuator/info");

    assertThat(Arrays.stream(ApiSecurityWebConfig.PUBLIC_GET_ROUTES)
        .anyMatch(route -> route.startsWith("/actuator") && route.startsWith("/api/")))
        .as("No actuator route may be exposed under /api")
        .isFalse();
  }

  // Reads management.endpoints.web.exposure.include from every application.yml on the classpath
  // (including the production src/main/resources copy that the test profile's copy shadows at runtime).
  private static List<String> productionActuatorWebExposureInclude() throws IOException {
    Resource[] resources = new PathMatchingResourcePatternResolver()
        .getResources("classpath*:application.yml");
    return Arrays.stream(resources)
        .map(ApiNonMvcSecuritySurfaceCoverageTest::exposureIncludeOf)
        .filter(value -> value != null && !value.isBlank())
        .toList();
  }

  private static String exposureIncludeOf(Resource resource) {
    YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(resource);
    Properties properties = yaml.getObject();
    return properties == null
        ? null
        : properties.getProperty("management.endpoints.web.exposure.include");
  }

  private static List<String> splitCsvLower(String value) {
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .map(token -> token.toLowerCase(java.util.Locale.ROOT))
        .filter(token -> !token.isBlank())
        .toList();
  }

  private void assertNotForbidden(String route) {
    assertThat(FORBIDDEN_PUBLIC_PREFIXES.stream().anyMatch(route::startsWith))
        .as("public route %s must not fall under a protected/sensitive family", route)
        .isFalse();
  }

  private static List<String> toSortedSignature(String[] routes) {
    return Arrays.stream(routes).sorted().toList();
  }

  private static List<String> applicationOwnedRegistrations(Map<String, ?> beans) {
    Map<String, String> owned = new TreeMap<>();
    beans.forEach((name, bean) -> {
      String beanClass = bean.getClass().getName();
      if (beanClass.startsWith(APP_PACKAGE)) {
        owned.put(name, beanClass);
      }
    });
    return owned.values().stream().toList();
  }
}
