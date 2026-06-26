package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.security.policy.TenantPolicyException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * OP-CAP-51 — route-edge permission mapping for the internal owner-company support surface
 * ({@code /api/v1/internal/support/**}). Pure unit test (no Spring context). Proves each support verb maps
 * to its dedicated {@code STAFF_*} permission, that a weaker support read can never reach grant-management /
 * maintenance / data-repair, and — critically — that a tenant operator/admin permission header can never
 * satisfy a support route. The default-deny posture of the policy is unchanged for everything else.
 */
class SupportAccessRoutePermissionTest {
  private final ApiPermissionInterceptor interceptor =
      new ApiPermissionInterceptor(new ApiPermissionGuard(), new ApiRouteSecurityPolicy());
  private static final Object HANDLER = new Object();
  private static final String TENANT = "123e4567-e89b-12d3-a456-426614174000";
  private static final String DIAGNOSTICS = "/api/v1/internal/support/tenants/" + TENANT + "/diagnostics";
  private static final String GRANTS = "/api/v1/internal/support/access-grants";
  private static final String MAINTENANCE = "/api/v1/internal/support/tenants/" + TENANT + "/maintenance-records";
  private static final String DATA_REPAIR =
      "/api/v1/internal/support/tenants/" + TENANT + "/data-repair-requests/dry-run";

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

  // --- diagnostics (read) requires STAFF_SUPPORT_READ ---

  @Test
  void diagnosticsGetWithStaffSupportReadSucceeds() {
    allow("GET", DIAGNOSTICS, "STAFF_SUPPORT_READ");
  }

  @Test
  void diagnosticsGetWithoutPermissionIsRejected() {
    deny("GET", DIAGNOSTICS, null, "STAFF_SUPPORT_READ");
  }

  @Test
  void diagnosticsGetWithTenantOperatorPermissionIsRejected() {
    // A tenant operator/admin permission header must NEVER reach an internal support route.
    deny("GET", DIAGNOSTICS, "REVIEW_READ", "STAFF_SUPPORT_READ");
    deny("GET", DIAGNOSTICS, "ADMIN_SETTINGS_READ", "STAFF_SUPPORT_READ");
    deny("GET", DIAGNOSTICS, "ANALYTICS_READ", "STAFF_SUPPORT_READ");
  }

  // --- access grant management requires STAFF_SUPPORT_GRANT_MANAGE (read can't manage) ---

  @Test
  void createGrantWithGrantManageSucceeds() {
    allow("POST", GRANTS, "STAFF_SUPPORT_GRANT_MANAGE");
  }

  @Test
  void createGrantWithStaffSupportReadAloneIsRejected() {
    deny("POST", GRANTS, "STAFF_SUPPORT_READ", "STAFF_SUPPORT_GRANT_MANAGE");
  }

  @Test
  void revokeGrantWithGrantManageSucceeds() {
    allow("POST", GRANTS + "/" + TENANT + "/revoke", "STAFF_SUPPORT_GRANT_MANAGE");
  }

  @Test
  void listGrantsGetRequiresStaffSupportRead() {
    allow("GET", "/api/v1/internal/support/tenants/" + TENANT + "/access-grants", "STAFF_SUPPORT_READ");
    deny("GET", "/api/v1/internal/support/tenants/" + TENANT + "/access-grants",
        "ADMIN_SETTINGS_MANAGE", "STAFF_SUPPORT_READ");
  }

  // --- maintenance record requires STAFF_MAINTENANCE_RECORD ---

  @Test
  void maintenanceRecordWithMaintenancePermissionSucceeds() {
    allow("POST", MAINTENANCE, "STAFF_MAINTENANCE_RECORD");
  }

  @Test
  void maintenanceRecordWithStaffSupportReadAloneIsRejected() {
    deny("POST", MAINTENANCE, "STAFF_SUPPORT_READ", "STAFF_MAINTENANCE_RECORD");
  }

  @Test
  void maintenanceRecordWithoutPermissionIsRejected() {
    deny("POST", MAINTENANCE, null, "STAFF_MAINTENANCE_RECORD");
  }

  // --- data-repair dry-run requires STAFF_DATA_REPAIR_DRYRUN ---

  @Test
  void dataRepairDryRunWithDataRepairPermissionSucceeds() {
    allow("POST", DATA_REPAIR, "STAFF_DATA_REPAIR_DRYRUN");
  }

  @Test
  void dataRepairDryRunWithMaintenancePermissionAloneIsRejected() {
    deny("POST", DATA_REPAIR, "STAFF_MAINTENANCE_RECORD", "STAFF_DATA_REPAIR_DRYRUN");
  }

  @Test
  void dataRepairDryRunWithTenantOperatorPermissionIsRejected() {
    deny("POST", DATA_REPAIR, "REVIEW_ACTION", "STAFF_DATA_REPAIR_DRYRUN");
  }
}
