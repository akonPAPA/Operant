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
  private static final String OPERATIONS_SUMMARY =
      "/api/v1/internal/support/tenants/" + TENANT + "/operations/summary";
  private static final String OPERATIONS_TIMELINE =
      "/api/v1/internal/support/tenants/" + TENANT + "/operations/timeline";
  private static final String OPERATIONS_DETAIL =
      "/api/v1/internal/support/tenants/" + TENANT + "/data-repair-requests/" + TENANT + "/operations-view";
  private static final String TENANT_SEARCH = "/api/v1/internal/support/tenants/search";
  private static final String SUPPORT_CONTEXT =
      "/api/v1/internal/support/tenants/" + TENANT + "/support-context";

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

  private void denyUnclassified(String method, String path, String permissionOrNull) {
    MockHttpServletRequest req = new MockHttpServletRequest(method, path);
    if (permissionOrNull != null) {
      req.addHeader(ApiPermissionGuard.PERMISSIONS_HEADER, permissionOrNull);
    }
    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("Unclassified API route");
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

  // --- OP-CAP-55: read-only operations visibility requires STAFF_SUPPORT_READ ---

  @Test
  void operationsVisibilityGetRequiresStaffSupportRead() {
    allow("GET", OPERATIONS_SUMMARY, "STAFF_SUPPORT_READ");
    allow("GET", OPERATIONS_TIMELINE, "STAFF_SUPPORT_READ");
    allow("GET", OPERATIONS_DETAIL, "STAFF_SUPPORT_READ");
    deny("GET", OPERATIONS_SUMMARY, "ADMIN_SETTINGS_READ", "STAFF_SUPPORT_READ");
    deny("GET", OPERATIONS_TIMELINE, "STAFF_DATA_REPAIR_DRYRUN", "STAFF_SUPPORT_READ");
    deny("GET", OPERATIONS_DETAIL, "REVIEW_READ", "STAFF_SUPPORT_READ");
  }

  @Test
  void operationsVisibilityWriteShapedRoutesAreDefaultDenied() {
    denyUnclassified("POST", OPERATIONS_SUMMARY, "STAFF_SUPPORT_READ");
    denyUnclassified("POST", OPERATIONS_DETAIL, "STAFF_DATA_REPAIR_DRYRUN");
  }

  // --- OP-CAP-57: tenant locator + support context require STAFF_SUPPORT_READ (never a tenant permission) ---

  @Test
  void tenantLocatorAndSupportContextGetRequireStaffSupportRead() {
    allow("GET", TENANT_SEARCH, "STAFF_SUPPORT_READ");
    allow("GET", SUPPORT_CONTEXT, "STAFF_SUPPORT_READ");
    deny("GET", TENANT_SEARCH, "ADMIN_SETTINGS_READ", "STAFF_SUPPORT_READ");
    deny("GET", TENANT_SEARCH, "REVIEW_READ", "STAFF_SUPPORT_READ");
    deny("GET", SUPPORT_CONTEXT, "ADMIN_SETTINGS_MANAGE", "STAFF_SUPPORT_READ");
    deny("GET", SUPPORT_CONTEXT, null, "STAFF_SUPPORT_READ");
  }

  @Test
  void tenantLocatorWriteShapedRoutesAreDefaultDenied() {
    denyUnclassified("POST", TENANT_SEARCH, "STAFF_SUPPORT_READ");
    denyUnclassified("POST", SUPPORT_CONTEXT, "STAFF_SUPPORT_READ");
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

  // --- OP-CAP-52: grant approval/rejection requires STAFF_SUPPORT_GRANT_APPROVE (distinct from MANAGE) ---

  private static final String GRANT_APPROVE = GRANTS + "/" + TENANT + "/approve";
  private static final String GRANT_REJECT = GRANTS + "/" + TENANT + "/reject";

  @Test
  void grantApproveRequiresGrantApprovePermission() {
    allow("POST", GRANT_APPROVE, "STAFF_SUPPORT_GRANT_APPROVE");
    allow("POST", GRANT_REJECT, "STAFF_SUPPORT_GRANT_APPROVE");
  }

  @Test
  void grantApproveWithGrantManageAloneIsRejected() {
    // The actor who mints a grant (STAFF_SUPPORT_GRANT_MANAGE) cannot also approve it from that permission.
    deny("POST", GRANT_APPROVE, "STAFF_SUPPORT_GRANT_MANAGE", "STAFF_SUPPORT_GRANT_APPROVE");
    deny("POST", GRANT_REJECT, "STAFF_SUPPORT_GRANT_MANAGE", "STAFF_SUPPORT_GRANT_APPROVE");
  }

  @Test
  void grantApproveWithTenantOperatorPermissionIsRejected() {
    deny("POST", GRANT_APPROVE, "REVIEW_ACTION", "STAFF_SUPPORT_GRANT_APPROVE");
  }

  // --- OP-CAP-52: data-repair approval/rejection requires STAFF_DATA_REPAIR_APPROVE ---

  private static final String REQUEST_BASE =
      "/api/v1/internal/support/tenants/" + TENANT + "/data-repair-requests/" + TENANT;

  @Test
  void dataRepairApproveRequiresDataRepairApprovePermission() {
    allow("POST", REQUEST_BASE + "/approve", "STAFF_DATA_REPAIR_APPROVE");
    allow("POST", REQUEST_BASE + "/reject", "STAFF_DATA_REPAIR_APPROVE");
  }

  @Test
  void dataRepairApproveWithDryRunPermissionAloneIsRejected() {
    // The requester (dry-run tier) cannot self-approve from the dry-run permission.
    deny("POST", REQUEST_BASE + "/approve", "STAFF_DATA_REPAIR_DRYRUN", "STAFF_DATA_REPAIR_APPROVE");
    deny("POST", REQUEST_BASE + "/reject", "STAFF_DATA_REPAIR_DRYRUN", "STAFF_DATA_REPAIR_APPROVE");
  }

  // --- OP-CAP-52: request-approval stays at the requester tier STAFF_DATA_REPAIR_DRYRUN ---

  @Test
  void dataRepairRequestApprovalRequiresDryRunTierPermission() {
    allow("POST", REQUEST_BASE + "/request-approval", "STAFF_DATA_REPAIR_DRYRUN");
    deny("POST", REQUEST_BASE + "/request-approval", "STAFF_DATA_REPAIR_APPROVE", "STAFF_DATA_REPAIR_DRYRUN");
  }

  // --- OP-CAP-52: execution attempt requires the strongest STAFF_DATA_REPAIR_EXECUTION_ATTEMPT ---

  @Test
  void dataRepairExecuteRequiresExecutionAttemptPermission() {
    allow("POST", REQUEST_BASE + "/execute", "STAFF_DATA_REPAIR_EXECUTION_ATTEMPT");
  }

  @Test
  void dataRepairExecuteWithWeakerSupportPermissionsIsRejected() {
    deny("POST", REQUEST_BASE + "/execute", "STAFF_DATA_REPAIR_APPROVE", "STAFF_DATA_REPAIR_EXECUTION_ATTEMPT");
    deny("POST", REQUEST_BASE + "/execute", "STAFF_DATA_REPAIR_DRYRUN", "STAFF_DATA_REPAIR_EXECUTION_ATTEMPT");
    deny("POST", REQUEST_BASE + "/execute", "REVIEW_ACTION", "STAFF_DATA_REPAIR_EXECUTION_ATTEMPT");
  }

  // --- Unknown internal support sub-routes remain unclassified and hit the global /api/** default-deny ---

  @Test
  void unknownInternalSupportRoutesAreDefaultDenied() {
    denyUnclassified(
        "POST", "/api/v1/internal/support/tenants/" + TENANT + "/unknown-action", "REVIEW_ACTION");
    denyUnclassified(
        "GET", "/api/v1/internal/support/tenants/" + TENANT + "/unknown-thing", "ADMIN_SETTINGS_READ");
  }
}
