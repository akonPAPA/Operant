package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.security.policy.TenantPolicyException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * OP-CAP-44E — default-deny behaviour of the route security policy and the interceptor that enforces
 * it. Pure unit test (no Spring context). Proves:
 * <ul>
 *   <li>an unknown / unclassified protected /api/** route fails closed (the interceptor throws rather
 *       than silently permitting);</li>
 *   <li>a classified protected route denies a request with a missing or wrong permission;</li>
 *   <li>the same route allows a request that carries the correct permission.</li>
 * </ul>
 */
class ApiRouteSecurityPolicyDefaultDenyTest {
  private final ApiRouteSecurityPolicy policy = new ApiRouteSecurityPolicy();
  private final ApiPermissionInterceptor interceptor =
      new ApiPermissionInterceptor(new ApiPermissionGuard(), policy);
  private static final Object HANDLER = new Object();

  @Test
  void unknownApiRouteHasNoClassification() {
    assertThat(policy.classify("GET", "/api/v2/future-feature/widget")).isEmpty();
    assertThat(policy.classify("GET", "/api/internal/secret-tool")).isEmpty();
    assertThat(policy.classify("POST", "/api/v1/totally-unmapped-group")).isEmpty();
  }

  @Test
  void unclassifiedProtectedRouteFailsClosedInInterceptor() {
    // Even an authenticated caller carrying a real permission must be denied on an unclassified route:
    // the interceptor throws instead of falling through to "permit".
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v2/future-feature/widget");
    req.addHeader(ApiPermissionGuard.PERMISSIONS_HEADER, ApiPermission.ANALYTICS_READ.name());

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("Unclassified API route GET /api/v2/future-feature/widget");
  }

  @Test
  void classifiedRouteWithMissingPermissionIsDenied() {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/analytics/overview");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("ANALYTICS_READ");
  }

  @Test
  void classifiedRouteWithWrongPermissionIsDenied() {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/analytics/overview");
    req.addHeader(ApiPermissionGuard.PERMISSIONS_HEADER, ApiPermission.QUOTE_READ.name());

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("ANALYTICS_READ");
  }

  @Test
  void classifiedRouteWithCorrectPermissionPasses() {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/analytics/overview");
    req.addHeader(ApiPermissionGuard.PERMISSIONS_HEADER, ApiPermission.ANALYTICS_READ.name());

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void mutationRequiresStrongerPermissionThanRead() {
    MockHttpServletRequest readOnly = new MockHttpServletRequest("POST", "/api/stage8/reconciliation/refresh");
    readOnly.addHeader(ApiPermissionGuard.PERMISSIONS_HEADER, ApiPermission.ANALYTICS_READ.name());
    assertThatThrownBy(() -> interceptor.preHandle(readOnly, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("ANALYTICS_MANAGE");

    MockHttpServletRequest manage = new MockHttpServletRequest("POST", "/api/stage8/reconciliation/refresh");
    manage.addHeader(ApiPermissionGuard.PERMISSIONS_HEADER, ApiPermission.ANALYTICS_MANAGE.name());
    assertThatNoException().isThrownBy(() -> interceptor.preHandle(manage, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void optionsPreflightIsNeverTreatedAsAClassifiedBusinessRoute() {
    // Preflight must short-circuit (allowed) and must not be classified as a protected business route.
    assertThat(policy.classify("OPTIONS", "/api/v1/analytics/overview")).isEmpty();

    MockHttpServletRequest req = new MockHttpServletRequest("OPTIONS", "/api/v1/analytics/overview");
    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }
}
