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
      // OP-CAP-46C: public-with-token secure order-journey tracking link (opaque expiring token is the
      // sole credential; tenant/journey scope is derived from the token, never from the request).
      "GET /api/v1/public/order-tracking/{token}",
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

  // OP-CAP-42B: the v1 change-request controller exposes the real external-write-adjacent lifecycle
  // verbs (approve / approve-internal / reject / cancel / execution-disabled) plus bare create. Each
  // must classify to its specific permission, distinct from the others. A regression that collapses
  // any of these onto a weaker permission would let a less-privileged client reach approval or
  // external-write-adjacent actions.
  @ParameterizedTest
  @MethodSource("changeRequestLifecycleRoutes")
  void v1ChangeRequestLifecycleVerbsMapToDistinctPermissions(RouteExpectation route) {
    RouteDecision decision = policy.classify(route.method(), route.path()).orElseThrow();

    assertThat(decision.classification()).isEqualTo(route.classification());
    assertThat(decision.requiredPermission()).isEqualTo(route.permission());
  }

  // OP-CAP-42B: privilege separation. CHANGE_REQUEST_CREATE is the weakest permission in the family
  // and must NOT satisfy approve / approve-internal / reject / cancel / execute / retry /
  // execution-disabled. This proves a create-only client cannot reach approval or external-write
  // execution by reusing its create grant (no approval/external-write bypass via weaker permission).
  @Test
  void changeRequestCreatePermissionDoesNotSatisfyApprovalOrExecuteAdjacentVerbs() {
    String base = "/api/v1/change-requests/123e4567-e89b-12d3-a456-426614174000";
    List<String> elevatedVerbs = List.of(
        base + "/approve",
        base + "/approve-internal",
        base + "/reject",
        base + "/cancel",
        base + "/execution-disabled");

    for (String path : elevatedVerbs) {
      ApiPermission required = policy.classify("POST", path).orElseThrow().requiredPermission();
      assertThat(required)
          .as("POST %s must require a permission stronger than CHANGE_REQUEST_CREATE", path)
          .isNotEqualTo(ApiPermission.CHANGE_REQUEST_CREATE);
      assertThat(required)
          .as("POST %s must be approve/reject/execute gated", path)
          .isIn(
              ApiPermission.CHANGE_REQUEST_APPROVE,
              ApiPermission.CHANGE_REQUEST_REJECT,
              ApiPermission.CHANGE_REQUEST_EXECUTE);
    }

    // The bare create route remains create-gated (control: not over-escalated either).
    assertThat(policy.classify("POST", "/api/v1/change-requests").orElseThrow().requiredPermission())
        .isEqualTo(ApiPermission.CHANGE_REQUEST_CREATE);
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
            "/api/v1/commerce-intelligence/demo-flow",
            SecurityClassification.PROTECTED_READ,
            ApiPermission.ANALYTICS_READ),
        // OP-CAP-27D: read-only runtime-control telemetry for the RFQ/AI/demo path is an operator read
        // (ANALYTICS_READ), NOT the platform RUNTIME_ENTITLEMENT_READ. The "/api/v1/runtime-control"
        // rule must win over the "/api/v1/runtime" entitlement prefix (proven here) so a demo-telemetry
        // reader can never inherit runtime governance authority.
        new RouteExpectation(
            "GET",
            "/api/v1/runtime-control/demo-flow",
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
            ApiPermission.RUNTIME_ENTITLEMENT_MANAGE),
        // OP-CAP-46B: the customer-safe read is a protected operator read (ANALYTICS_READ), NOT a
        // public route — there is no public buyer tracking gateway in this slice.
        new RouteExpectation(
            "GET",
            "/api/v1/order-journeys/123e4567-e89b-12d3-a456-426614174000/customer-safe",
            SecurityClassification.PROTECTED_READ,
            ApiPermission.ANALYTICS_READ),
        // OP-CAP-46B: the manual milestone mutation is an audited operator action. It maps to the
        // documented order-journey mutation permission (REVIEW_ACTION) — the same gate as the sibling
        // POST /{id}/signals path, which can likewise confirm a MANUAL DELIVERED milestone.
        new RouteExpectation(
            "POST",
            "/api/v1/order-journeys/123e4567-e89b-12d3-a456-426614174000/manual-milestones",
            SecurityClassification.PROTECTED_CREATE,
            ApiPermission.REVIEW_ACTION),
        // OP-CAP-46C: minting a secure tracking link is an audited operator action under the
        // order-journey prefix — REVIEW_ACTION, the same gate as the sibling signal/milestone writes.
        new RouteExpectation(
            "POST",
            "/api/v1/order-journeys/123e4567-e89b-12d3-a456-426614174000/tracking-links",
            SecurityClassification.PROTECTED_CREATE,
            ApiPermission.REVIEW_ACTION),
        // OP-CAP-46G: revoking a secure tracking link is likewise an audited operator action under the
        // order-journey prefix — protected REVIEW_ACTION, never public. The public-with-token GET
        // resolve route is unaffected (proven by secureTrackingLinkResolveRouteIsPublicWithToken).
        new RouteExpectation(
            "POST",
            "/api/v1/order-journeys/123e4567-e89b-12d3-a456-426614174000/tracking-links/"
                + "223e4567-e89b-12d3-a456-426614174000/revoke",
            SecurityClassification.PROTECTED_CREATE,
            ApiPermission.REVIEW_ACTION),
        // OP-CAP-46H: the operator tracking-link registry list is a protected operator read under the
        // order-journey prefix — PROTECTED_READ / ANALYTICS_READ, the same gate as the sibling
        // customer-safe read. It is never public (only the GET /api/v1/public/order-tracking/{token}
        // resolve route is public-with-token).
        new RouteExpectation(
            "GET",
            "/api/v1/order-journeys/123e4567-e89b-12d3-a456-426614174000/tracking-links",
            SecurityClassification.PROTECTED_READ,
            ApiPermission.ANALYTICS_READ),
        // OP-CAP-47A: the operator fulfillment visibility timeline is a protected operator read under
        // the order-journey prefix — PROTECTED_READ / ANALYTICS_READ, the same gate as the sibling
        // detail/customer-safe reads. It is never public (only the GET /api/v1/public/order-tracking/
        // {token} resolve route is public-with-token).
        new RouteExpectation(
            "GET",
            "/api/v1/order-journeys/123e4567-e89b-12d3-a456-426614174000/operator-timeline",
            SecurityClassification.PROTECTED_READ,
            ApiPermission.ANALYTICS_READ),
        // OP-CAP-51: the internal owner-company support surface. Each verb maps to a dedicated STAFF_*
        // permission (never a tenant business permission), proving the route-edge separation.
        new RouteExpectation(
            "GET",
            "/api/v1/internal/support/tenants/123e4567-e89b-12d3-a456-426614174000/diagnostics",
            SecurityClassification.PROTECTED_READ,
            ApiPermission.STAFF_SUPPORT_READ),
        // OP-CAP-57: the read-only internal tenant locator + per-tenant support context are STAFF_SUPPORT_READ.
        new RouteExpectation(
            "GET",
            "/api/v1/internal/support/tenants/search",
            SecurityClassification.PROTECTED_READ,
            ApiPermission.STAFF_SUPPORT_READ),
        new RouteExpectation(
            "GET",
            "/api/v1/internal/support/tenants/123e4567-e89b-12d3-a456-426614174000/support-context",
            SecurityClassification.PROTECTED_READ,
            ApiPermission.STAFF_SUPPORT_READ),
        new RouteExpectation(
            "POST",
            "/api/v1/internal/support/access-grants",
            SecurityClassification.PROTECTED_CREATE,
            ApiPermission.STAFF_SUPPORT_GRANT_MANAGE),
        new RouteExpectation(
            "POST",
            "/api/v1/internal/support/tenants/123e4567-e89b-12d3-a456-426614174000/maintenance-records",
            SecurityClassification.PROTECTED_CREATE,
            ApiPermission.STAFF_MAINTENANCE_RECORD),
        new RouteExpectation(
            "POST",
            "/api/v1/internal/support/tenants/123e4567-e89b-12d3-a456-426614174000/data-repair-requests/dry-run",
            SecurityClassification.PROTECTED_CREATE,
            ApiPermission.STAFF_DATA_REPAIR_DRYRUN),
        // OP-CAP-52: the approval/execution verbs map to dedicated, stronger STAFF_* permissions so a
        // grant-creator / dry-run requester cannot reach approval, and only the execution-attempt
        // permission reaches the (disabled) execute stub.
        new RouteExpectation(
            "POST",
            "/api/v1/internal/support/access-grants/123e4567-e89b-12d3-a456-426614174111/approve",
            SecurityClassification.PROTECTED_APPROVE,
            ApiPermission.STAFF_SUPPORT_GRANT_APPROVE),
        new RouteExpectation(
            "POST",
            "/api/v1/internal/support/tenants/123e4567-e89b-12d3-a456-426614174000/data-repair-requests/"
                + "123e4567-e89b-12d3-a456-426614174222/approve",
            SecurityClassification.PROTECTED_APPROVE,
            ApiPermission.STAFF_DATA_REPAIR_APPROVE),
        new RouteExpectation(
            "POST",
            "/api/v1/internal/support/tenants/123e4567-e89b-12d3-a456-426614174000/data-repair-requests/"
                + "123e4567-e89b-12d3-a456-426614174222/execute",
            SecurityClassification.PROTECTED_EXECUTE,
            ApiPermission.STAFF_DATA_REPAIR_EXECUTION_ATTEMPT),
        // OP-CAP-54: the ONE bounded real-execution verb requires its own dedicated, stronger permission,
        // distinct from the generic execute stub above. It is the only data-repair route that may mutate a
        // processing_job row, and only for the PROCESSING_JOB_STATUS_REPAIR target.
        new RouteExpectation(
            "POST",
            "/api/v1/internal/support/tenants/123e4567-e89b-12d3-a456-426614174000/data-repair-requests/"
                + "123e4567-e89b-12d3-a456-426614174222/execute-processing-job-repair",
            SecurityClassification.PROTECTED_EXECUTE,
            ApiPermission.STAFF_PROCESSING_JOB_REPAIR_EXECUTE));
  }

  // OP-CAP-46C: the public secure tracking link is classified public-with-token (no permission), while
  // its sibling minting/read order-journey routes remain permission-protected. Scope is proven by the
  // opaque expiring token, not by any request authority field.

  // OP-CAP-46C: the public secure tracking link is classified public-with-token (no permission), while
  // its sibling minting/read order-journey routes remain permission-protected. Scope is proven by the
  // opaque expiring token, not by any request authority field.
  // OP-CAP-27D follow-up (PR #253): the read-only runtime-control telemetry surface must not inherit the
  // generic "/api/v1/runtime" governance write rule just because the paths share a prefix. GET is the
  // operator read (ANALYTICS_READ); every non-GET verb fails closed (default-deny) rather than mapping to
  // RUNTIME_ENTITLEMENT_MANAGE — there is no runtime-control write endpoint in this slice.
  @Test
  void runtimeControlNonGetIsDefaultDeniedAndNeverInheritsRuntimeEntitlementManage() {
    assertThat(policy.classify("GET", "/api/v1/runtime-control/demo-flow"))
        .hasValueSatisfying(decision -> {
          assertThat(decision.classification()).isEqualTo(SecurityClassification.PROTECTED_READ);
          assertThat(decision.requiredPermission()).isEqualTo(ApiPermission.ANALYTICS_READ);
        });

    for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
      assertThat(policy.classify(method, "/api/v1/runtime-control/demo-flow"))
          .as("%s /api/v1/runtime-control/demo-flow must be default-denied (unclassified)", method)
          .isEmpty();
      assertThat(policy.classify(method, "/api/v1/runtime-control/anything-else"))
          .as("%s /api/v1/runtime-control/anything-else must be default-denied (unclassified)", method)
          .isEmpty();
    }

    // Control: the sibling "/api/v1/runtime" governance surface still maps non-GET to the manage
    // permission, proving the runtime-control carve-out did not weaken the real runtime rule.
    assertThat(policy.classify("POST", "/api/v1/runtime/plans"))
        .hasValueSatisfying(decision ->
            assertThat(decision.requiredPermission()).isEqualTo(ApiPermission.RUNTIME_ENTITLEMENT_MANAGE));
  }

  @Test
  void secureTrackingLinkResolveRouteIsPublicWithToken() {
    RouteDecision decision = policy.classify(
        "GET", "/api/v1/public/order-tracking/some-opaque-token").orElseThrow();

    assertThat(decision.isPublic()).isTrue();
    assertThat(decision.requiredPermission()).isNull();
    assertThat(decision.classification())
        .isEqualTo(SecurityClassification.SECURE_TRACKING_LINK_PUBLIC_WITH_TOKEN);
  }

  private static Stream<RouteExpectation> changeRequestLifecycleRoutes() {
    String base = "/api/v1/change-requests/123e4567-e89b-12d3-a456-426614174000";
    return Stream.of(
        new RouteExpectation(
            "POST",
            "/api/v1/change-requests",
            SecurityClassification.PROTECTED_CREATE,
            ApiPermission.CHANGE_REQUEST_CREATE),
        new RouteExpectation(
            "POST",
            base + "/approve",
            SecurityClassification.PROTECTED_APPROVE,
            ApiPermission.CHANGE_REQUEST_APPROVE),
        new RouteExpectation(
            "POST",
            base + "/approve-internal",
            SecurityClassification.PROTECTED_APPROVE,
            ApiPermission.CHANGE_REQUEST_APPROVE),
        new RouteExpectation(
            "POST",
            base + "/reject",
            SecurityClassification.PROTECTED_REJECT,
            ApiPermission.CHANGE_REQUEST_REJECT),
        new RouteExpectation(
            "POST",
            base + "/cancel",
            SecurityClassification.PROTECTED_REJECT,
            ApiPermission.CHANGE_REQUEST_REJECT),
        new RouteExpectation(
            "POST",
            base + "/execution-disabled",
            SecurityClassification.PROTECTED_EXECUTE,
            ApiPermission.CHANGE_REQUEST_EXECUTE));
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
