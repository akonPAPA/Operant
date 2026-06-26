package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.security.ApiRouteSecurityPolicy.RouteDecision;
import com.orderpilot.security.ApiRouteSecurityPolicy.SecurityClassification;
import com.orderpilot.security.policy.TenantPolicyException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * OP-CAP-54 — route-edge permission mapping for the one bounded real-execution endpoint
 * ({@code .../data-repair-requests/{id}/execute-processing-job-repair}). Pure unit test (no Spring context).
 * Proves it requires its OWN dedicated, stronger {@code STAFF_PROCESSING_JOB_REPAIR_EXECUTE} permission —
 * not the generic execute-stub permission, not any weaker support permission, and never a tenant business
 * permission — while the generic /execute stub keeps its OP-CAP-52 mapping unchanged.
 */
class ProcessingJobRepairRoutePermissionTest {
  private final ApiRouteSecurityPolicy policy = new ApiRouteSecurityPolicy();
  private final ApiPermissionInterceptor interceptor =
      new ApiPermissionInterceptor(new ApiPermissionGuard(), policy);
  private static final Object HANDLER = new Object();
  private static final String TENANT = "123e4567-e89b-12d3-a456-426614174000";
  private static final String REQUEST = "223e4567-e89b-12d3-a456-426614174999";
  private static final String EXECUTE_REPAIR =
      "/api/v1/internal/support/tenants/" + TENANT + "/data-repair-requests/" + REQUEST
          + "/execute-processing-job-repair";
  private static final String EXECUTE_STUB =
      "/api/v1/internal/support/tenants/" + TENANT + "/data-repair-requests/" + REQUEST + "/execute";

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

  @Test
  void repairExecuteClassifiesToProtectedExecuteAndDedicatedPermission() {
    RouteDecision decision = policy.classify("POST", EXECUTE_REPAIR).orElseThrow();
    assertThat(decision.classification()).isEqualTo(SecurityClassification.PROTECTED_EXECUTE);
    assertThat(decision.requiredPermission()).isEqualTo(ApiPermission.STAFF_PROCESSING_JOB_REPAIR_EXECUTE);
  }

  @Test
  void repairExecuteRequiresDedicatedRepairExecutePermission() {
    allow("POST", EXECUTE_REPAIR, "STAFF_PROCESSING_JOB_REPAIR_EXECUTE");
  }

  @Test
  void repairExecuteRejectsWeakerSupportPermissions() {
    deny("POST", EXECUTE_REPAIR, "STAFF_DATA_REPAIR_EXECUTION_ATTEMPT", "STAFF_PROCESSING_JOB_REPAIR_EXECUTE");
    deny("POST", EXECUTE_REPAIR, "STAFF_DATA_REPAIR_APPROVE", "STAFF_PROCESSING_JOB_REPAIR_EXECUTE");
    deny("POST", EXECUTE_REPAIR, "STAFF_DATA_REPAIR_DRYRUN", "STAFF_PROCESSING_JOB_REPAIR_EXECUTE");
  }

  @Test
  void repairExecuteRejectsTenantOperatorPermissionAndAnonymous() {
    deny("POST", EXECUTE_REPAIR, "REVIEW_ACTION", "STAFF_PROCESSING_JOB_REPAIR_EXECUTE");
    deny("POST", EXECUTE_REPAIR, "ADMIN_SETTINGS_MANAGE", "STAFF_PROCESSING_JOB_REPAIR_EXECUTE");
    deny("POST", EXECUTE_REPAIR, null, "STAFF_PROCESSING_JOB_REPAIR_EXECUTE");
  }

  @Test
  void genericExecuteStubKeepsItsOwnExecutionAttemptPermission() {
    // The OP-CAP-54 repair permission must NOT satisfy the OP-CAP-52 generic execute stub, and vice versa.
    allow("POST", EXECUTE_STUB, "STAFF_DATA_REPAIR_EXECUTION_ATTEMPT");
    deny("POST", EXECUTE_STUB, "STAFF_PROCESSING_JOB_REPAIR_EXECUTE", "STAFF_DATA_REPAIR_EXECUTION_ATTEMPT");
  }
}
