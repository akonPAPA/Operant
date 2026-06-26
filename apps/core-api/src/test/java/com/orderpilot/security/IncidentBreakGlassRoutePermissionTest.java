package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.security.policy.TenantPolicyException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * OP-CAP-53 — route-edge permission mapping for the internal incident-response / break-glass surface
 * ({@code /api/v1/internal/support/**}). Pure unit test (no Spring context). Proves each incident/break-glass
 * verb maps to its dedicated {@code STAFF_*} permission, that distinct verbs require distinct permissions (a
 * requester cannot reach approval; an incident-creator cannot reach close), and — critically — that a tenant
 * operator/admin permission header can never satisfy a break-glass/incident route. Unknown internal-support
 * sub-routes still fail closed (proven in {@link SupportAccessRoutePermissionTest}).
 */
class IncidentBreakGlassRoutePermissionTest {
  private final ApiPermissionInterceptor interceptor =
      new ApiPermissionInterceptor(new ApiPermissionGuard(), new ApiRouteSecurityPolicy());
  private static final Object HANDLER = new Object();
  private static final String TENANT = "123e4567-e89b-12d3-a456-426614174000";
  private static final String REQ = "223e4567-e89b-12d3-a456-426614174111";
  private static final String INC = "323e4567-e89b-12d3-a456-426614174222";

  private static final String INCIDENTS = "/api/v1/internal/support/incidents";
  private static final String INCIDENT_GET = INCIDENTS + "/" + INC;
  private static final String INCIDENT_CLOSE = INCIDENTS + "/" + INC + "/close";
  private static final String BREAK_GLASS_CREATE =
      "/api/v1/internal/support/tenants/" + TENANT + "/incidents/" + INC + "/break-glass-requests";
  private static final String BG_BASE =
      "/api/v1/internal/support/tenants/" + TENANT + "/break-glass-requests/" + REQ;

  private void allow(String method, String path, String permission) {
    MockHttpServletRequest req = new MockHttpServletRequest(method, path);
    req.addHeader(ApiPermissionGuard.PERMISSIONS_HEADER, permission);
    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  private void deny(String method, String path, String permissionOrNull, String expectedMissing) {
    MockHttpServletRequest req = new MockHttpServletRequest(method, path);
    if (permissionOrNull != null) {
      req.addHeader(ApiPermissionGuard.PERMISSIONS_HEADER, permissionOrNull);
    }
    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining(expectedMissing);
  }

  // --- incident create/read/close map to distinct STAFF_INCIDENT_* permissions ---

  @Test
  void createIncidentRequiresIncidentCreate() {
    allow("POST", INCIDENTS, "STAFF_INCIDENT_CREATE");
  }

  @Test
  void createIncidentWithReadAloneIsRejected() {
    deny("POST", INCIDENTS, "STAFF_INCIDENT_READ", "STAFF_INCIDENT_CREATE");
  }

  @Test
  void getIncidentRequiresIncidentRead() {
    allow("GET", INCIDENT_GET, "STAFF_INCIDENT_READ");
    deny("GET", INCIDENT_GET, null, "STAFF_INCIDENT_READ");
  }

  @Test
  void closeIncidentRequiresIncidentClose() {
    allow("POST", INCIDENT_CLOSE, "STAFF_INCIDENT_CLOSE");
  }

  @Test
  void closeIncidentWithCreateOrReadAloneIsRejected() {
    deny("POST", INCIDENT_CLOSE, "STAFF_INCIDENT_CREATE", "STAFF_INCIDENT_CLOSE");
    deny("POST", INCIDENT_CLOSE, "STAFF_INCIDENT_READ", "STAFF_INCIDENT_CLOSE");
  }

  @Test
  void incidentRoutesRejectTenantOperatorPermission() {
    deny("POST", INCIDENTS, "REVIEW_ACTION", "STAFF_INCIDENT_CREATE");
    deny("GET", INCIDENT_GET, "ADMIN_SETTINGS_READ", "STAFF_INCIDENT_READ");
    deny("POST", INCIDENT_CLOSE, "ANALYTICS_MANAGE", "STAFF_INCIDENT_CLOSE");
  }

  // --- break-glass request/approve/reject/revoke map to distinct STAFF_BREAK_GLASS_* permissions ---

  @Test
  void requestBreakGlassRequiresBreakGlassRequest() {
    allow("POST", BREAK_GLASS_CREATE, "STAFF_BREAK_GLASS_REQUEST");
    deny("POST", BREAK_GLASS_CREATE, "STAFF_INCIDENT_CREATE", "STAFF_BREAK_GLASS_REQUEST");
  }

  @Test
  void approveBreakGlassRequiresBreakGlassApprove() {
    allow("POST", BG_BASE + "/approve", "STAFF_BREAK_GLASS_APPROVE");
    allow("POST", BG_BASE + "/reject", "STAFF_BREAK_GLASS_APPROVE");
  }

  @Test
  void approveBreakGlassWithRequestPermissionAloneIsRejected() {
    // The requester (STAFF_BREAK_GLASS_REQUEST) cannot self-approve from the request permission.
    deny("POST", BG_BASE + "/approve", "STAFF_BREAK_GLASS_REQUEST", "STAFF_BREAK_GLASS_APPROVE");
    deny("POST", BG_BASE + "/reject", "STAFF_BREAK_GLASS_REQUEST", "STAFF_BREAK_GLASS_APPROVE");
  }

  @Test
  void revokeBreakGlassRequiresBreakGlassRevoke() {
    allow("POST", BG_BASE + "/revoke", "STAFF_BREAK_GLASS_REVOKE");
    deny("POST", BG_BASE + "/revoke", "STAFF_BREAK_GLASS_APPROVE", "STAFF_BREAK_GLASS_REVOKE");
    deny("POST", BG_BASE + "/revoke", "STAFF_BREAK_GLASS_REQUEST", "STAFF_BREAK_GLASS_REVOKE");
  }

  @Test
  void breakGlassRoutesRejectTenantOperatorPermission() {
    deny("POST", BREAK_GLASS_CREATE, "REVIEW_ACTION", "STAFF_BREAK_GLASS_REQUEST");
    deny("POST", BG_BASE + "/approve", "REVIEW_ACTION", "STAFF_BREAK_GLASS_APPROVE");
    deny("POST", BG_BASE + "/revoke", "ADMIN_SETTINGS_MANAGE", "STAFF_BREAK_GLASS_REVOKE");
  }

  @Test
  void breakGlassCreateNestedUnderIncidentDoesNotResolveAsIncidentRoute() {
    // The create path contains "/incidents" but must classify as break-glass, not incident-create.
    deny("POST", BREAK_GLASS_CREATE, "STAFF_INCIDENT_CREATE", "STAFF_BREAK_GLASS_REQUEST");
  }
}
