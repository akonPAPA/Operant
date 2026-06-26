package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.security.ApiRolePermissionMatrix.RoleProfile;
import com.orderpilot.security.policy.TenantPolicyException;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * OP-CAP-45A — RBAC foundation tests. Proves the role → permission matrix is deterministic and that
 * representative role profiles produce the expected allow/deny behaviour through the REAL route policy
 * and permission interceptor (i.e. the matrix is not a paper artifact; it drives actual enforcement).
 */
class ApiPermissionRoleMatrixTest {
  private final ApiRouteSecurityPolicy policy = new ApiRouteSecurityPolicy();
  private final ApiPermissionInterceptor interceptor =
      new ApiPermissionInterceptor(new ApiPermissionGuard(), policy);
  private static final Object HANDLER = new Object();

  // representative protected routes pulled from the production ApiRouteSecurityPolicy
  private static final String ANALYTICS_READ_ROUTE = "/api/v1/analytics/overview";
  private static final String STAGE8_REFRESH_ROUTE = "/api/stage8/reconciliation/refresh";
  private static final String QUOTE_REVIEW_ACTION_ROUTE = "/api/v1/quote-review/123/assemble-draft";
  private static final String CR_APPROVE_ROUTE = "/api/stage9/change-requests/123/approve";
  private static final String CR_EXECUTE_ROUTE = "/api/stage9/change-requests/123/execute";

  // --- structural matrix invariants ---

  @Test
  void ownerAdminHoldsEveryTenantPermissionAndIsSupersetOfEveryRole() {
    // OP-CAP-51: OWNER_ADMIN holds every TENANT permission, but NOT the internal staff/support family —
    // a tenant owner is not Operant-owner-company support staff.
    EnumSet<ApiPermission> everyTenantPermission = EnumSet.allOf(ApiPermission.class);
    everyTenantPermission.removeAll(ApiRolePermissionMatrix.STAFF_SUPPORT_PERMISSIONS);
    assertThat(ApiRolePermissionMatrix.permissionsFor(RoleProfile.OWNER_ADMIN))
        .containsExactlyInAnyOrderElementsOf(everyTenantPermission);

    for (RoleProfile role : RoleProfile.values()) {
      assertThat(ApiRolePermissionMatrix.permissionsFor(RoleProfile.OWNER_ADMIN))
          .as("OWNER_ADMIN must be a superset of %s", role)
          .containsAll(ApiRolePermissionMatrix.permissionsFor(role));
    }
  }

  @Test
  void noTenantRoleHoldsAnyStaffSupportPermission() {
    // OP-CAP-51: the STAFF_* family is reserved for Operant-owner-company staff and must never leak into a
    // tenant role profile (not even OWNER_ADMIN or AUDITOR). This proves support access cannot be reached
    // via any tenant permission grant.
    assertThat(ApiRolePermissionMatrix.STAFF_SUPPORT_PERMISSIONS).isNotEmpty();
    for (RoleProfile role : RoleProfile.values()) {
      assertThat(ApiRolePermissionMatrix.permissionsFor(role))
          .as("%s must hold no STAFF_* support permission", role)
          .doesNotContainAnyElementsOf(ApiRolePermissionMatrix.STAFF_SUPPORT_PERMISSIONS);
    }
  }

  @Test
  void readOnlyRolesHoldNoMutationPermission() {
    for (RoleProfile role : EnumSet.of(RoleProfile.AUDITOR, RoleProfile.ANALYTICS_VIEWER)) {
      assertThat(ApiRolePermissionMatrix.permissionsFor(role))
          .as("%s must hold only read-only permissions", role)
          .allMatch(p -> p.name().endsWith("_READ"))
          .isSubsetOf(ApiRolePermissionMatrix.READ_ONLY_PERMISSIONS);
    }
  }

  @Test
  void onlyTrustedRolesCanReachExternalWriteExecute() {
    for (RoleProfile role : RoleProfile.values()) {
      boolean canExecute =
          ApiRolePermissionMatrix.permissionsFor(role).contains(ApiRolePermissionMatrix.EXTERNAL_EXECUTE_PERMISSION);
      boolean expected = role == RoleProfile.OWNER_ADMIN || role == RoleProfile.INTEGRATION_ADMIN;
      assertThat(canExecute)
          .as("%s external-write-execute grant", role)
          .isEqualTo(expected);
    }
  }

  @Test
  void salesManagerCanApproveButNotExecuteAndOperatorCanDoNeither() {
    assertThat(ApiRolePermissionMatrix.permissionsFor(RoleProfile.SALES_MANAGER))
        .contains(ApiPermission.CHANGE_REQUEST_APPROVE)
        .doesNotContain(ApiPermission.CHANGE_REQUEST_EXECUTE);
    assertThat(ApiRolePermissionMatrix.permissionsFor(RoleProfile.OPERATOR))
        .doesNotContain(ApiPermission.CHANGE_REQUEST_APPROVE, ApiPermission.CHANGE_REQUEST_EXECUTE);
  }

  @Test
  void matrixIsDeterministicAndImmutable() {
    assertThat(ApiRolePermissionMatrix.permissionsFor(RoleProfile.OPERATOR))
        .isEqualTo(ApiRolePermissionMatrix.permissionsFor(RoleProfile.OPERATOR));
    org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, () ->
        ApiRolePermissionMatrix.permissionsFor(RoleProfile.OPERATOR).add(ApiPermission.CHANGE_REQUEST_EXECUTE));
  }

  // --- the matrix drives real route enforcement (allow/deny per role) ---

  @Test
  void analyticsViewerCanReadAnalyticsButCannotRunAnyMutation() {
    allow(RoleProfile.ANALYTICS_VIEWER, "GET", ANALYTICS_READ_ROUTE);
    deny(RoleProfile.ANALYTICS_VIEWER, "POST", STAGE8_REFRESH_ROUTE, "ANALYTICS_MANAGE");
    deny(RoleProfile.ANALYTICS_VIEWER, "POST", QUOTE_REVIEW_ACTION_ROUTE, "REVIEW_ACTION");
  }

  @Test
  void auditorCanReadEverywhereButCannotMutate() {
    allow(RoleProfile.AUDITOR, "GET", ANALYTICS_READ_ROUTE);
    allow(RoleProfile.AUDITOR, "GET", "/api/stage9/change-requests/123");
    deny(RoleProfile.AUDITOR, "POST", STAGE8_REFRESH_ROUTE, "ANALYTICS_MANAGE");
    deny(RoleProfile.AUDITOR, "POST", CR_APPROVE_ROUTE, "CHANGE_REQUEST_APPROVE");
  }

  @Test
  void operatorCanActOnReviewWorkButCannotApproveOrExecuteChangeRequests() {
    allow(RoleProfile.OPERATOR, "POST", QUOTE_REVIEW_ACTION_ROUTE);
    deny(RoleProfile.OPERATOR, "POST", CR_APPROVE_ROUTE, "CHANGE_REQUEST_APPROVE");
    deny(RoleProfile.OPERATOR, "POST", CR_EXECUTE_ROUTE, "CHANGE_REQUEST_EXECUTE");
  }

  @Test
  void salesManagerCanApproveChangeRequestsButCannotExecuteExternalWrite() {
    allow(RoleProfile.SALES_MANAGER, "POST", CR_APPROVE_ROUTE);
    deny(RoleProfile.SALES_MANAGER, "POST", CR_EXECUTE_ROUTE, "CHANGE_REQUEST_EXECUTE");
  }

  @Test
  void integrationAdminCanExecuteExternalWriteButCannotActAsAnOperator() {
    allow(RoleProfile.INTEGRATION_ADMIN, "POST", CR_EXECUTE_ROUTE);
    allow(RoleProfile.INTEGRATION_ADMIN, "POST", "/api/stage9/integrations/demo-erp");
    // INTEGRATION_ADMIN is not an operator: it cannot perform a quote-review operator action.
    deny(RoleProfile.INTEGRATION_ADMIN, "POST", QUOTE_REVIEW_ACTION_ROUTE, "REVIEW_ACTION");
  }

  private void allow(RoleProfile role, String method, String path) {
    MockHttpServletRequest req = new MockHttpServletRequest(method, path);
    req.addHeader(ApiPermissionGuard.PERMISSIONS_HEADER, ApiRolePermissionMatrix.permissionHeaderFor(role));
    assertThatNoException()
        .as("%s should be allowed on %s %s", role, method, path)
        .isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  private void deny(RoleProfile role, String method, String path, String expectedMissingPermission) {
    MockHttpServletRequest req = new MockHttpServletRequest(method, path);
    req.addHeader(ApiPermissionGuard.PERMISSIONS_HEADER, ApiRolePermissionMatrix.permissionHeaderFor(role));
    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .as("%s should be denied on %s %s", role, method, path)
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining(expectedMissingPermission);
  }
}
