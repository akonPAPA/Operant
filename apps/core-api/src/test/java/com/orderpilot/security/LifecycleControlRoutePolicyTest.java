package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.security.ApiRouteSecurityPolicy.RouteDecision;
import com.orderpilot.security.ApiRouteSecurityPolicy.SecurityClassification;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * P1-E2A - route-edge classification + principal-class separation for the durable backup control slice.
 * Proves each of the four routes maps to its own distinct control permission, that the staff-request and
 * executor families are disjoint, and that every wrong verb / unknown sub-path fails closed (unclassified
 * -> global default-deny).
 */
class LifecycleControlRoutePolicyTest {
  private final ApiRouteSecurityPolicy policy = new ApiRouteSecurityPolicy();

  private static final String BASE = "/api/v1/internal/control/lifecycle";
  private static final String OP =
      BASE + "/operations/op_0011223344556677889900aa";

  @Test
  void backupRequestRouteRequiresStaffBackupPermission() {
    RouteDecision decision = policy.classify("POST", BASE + "/backups").orElseThrow();
    assertThat(decision.classification()).isEqualTo(SecurityClassification.PROTECTED_CREATE);
    assertThat(decision.requiredPermission()).isEqualTo(ApiPermission.STAFF_CONTROL_BACKUP);
  }

  @Test
  void operationReadRouteRequiresStaffLifecycleReadPermission() {
    RouteDecision decision = policy.classify("GET", OP).orElseThrow();
    assertThat(decision.classification()).isEqualTo(SecurityClassification.PROTECTED_READ);
    assertThat(decision.requiredPermission()).isEqualTo(ApiPermission.STAFF_CONTROL_LIFECYCLE_READ);
  }

  @Test
  void executorLeaseRouteRequiresExecutorLeasePermission() {
    RouteDecision decision = policy.classify("POST", BASE + "/executor/lease").orElseThrow();
    assertThat(decision.classification()).isEqualTo(SecurityClassification.PROTECTED_EXECUTE);
    assertThat(decision.requiredPermission()).isEqualTo(ApiPermission.CONTROL_EXECUTOR_LEASE);
  }

  @Test
  void completeRouteRequiresExecutorReportPermission() {
    RouteDecision decision = policy.classify("POST", OP + "/complete").orElseThrow();
    assertThat(decision.classification()).isEqualTo(SecurityClassification.PROTECTED_EXECUTE);
    assertThat(decision.requiredPermission()).isEqualTo(ApiPermission.CONTROL_EXECUTOR_REPORT);
  }

  @Test
  void staffAndExecutorPermissionFamiliesAreDisjointAcrossRoutes() {
    ApiPermission backup = policy.classify("POST", BASE + "/backups").orElseThrow().requiredPermission();
    ApiPermission read = policy.classify("GET", OP).orElseThrow().requiredPermission();
    ApiPermission lease = policy.classify("POST", BASE + "/executor/lease").orElseThrow().requiredPermission();
    ApiPermission complete = policy.classify("POST", OP + "/complete").orElseThrow().requiredPermission();

    // A staff-request permission never satisfies an executor route, and vice versa.
    assertThat(backup).isNotIn(lease, complete);
    assertThat(read).isNotIn(lease, complete);
    assertThat(backup.name()).startsWith("STAFF_CONTROL_");
    assertThat(read.name()).startsWith("STAFF_CONTROL_");
    assertThat(lease.name()).startsWith("CONTROL_EXECUTOR_");
    assertThat(complete.name()).startsWith("CONTROL_EXECUTOR_");
  }

  @Test
  void wrongVerbsAndUnknownSubPathsFailClosed() {
    // Read route is GET-only; a POST/PUT/DELETE to it is not the complete route and is unclassified.
    for (String method : List.of("PUT", "DELETE", "PATCH")) {
      assertThat(policy.classify(method, BASE + "/backups")).isEmpty();
      assertThat(policy.classify(method, BASE + "/executor/lease")).isEmpty();
      assertThat(policy.classify(method, OP)).isEmpty();
      assertThat(policy.classify(method, OP + "/complete")).isEmpty();
    }
    // Executor lease is POST-only.
    assertThat(policy.classify("GET", BASE + "/executor/lease")).isEmpty();
    // The staff read route is GET-only: a POST to a bare operation id is not classified.
    assertThat(policy.classify("POST", OP)).isEmpty();
    // Backups is POST-only.
    assertThat(policy.classify("GET", BASE + "/backups")).isEmpty();
    // Unknown lifecycle sub-paths fail closed.
    assertThat(policy.classify("POST", BASE + "/restore")).isEmpty();
    assertThat(policy.classify("POST", BASE + "/upgrade")).isEmpty();
    assertThat(policy.classify("POST", BASE)).isEmpty();
    assertThat(policy.classify("GET", BASE + "/operations")).isEmpty();
    assertThat(policy.classify("POST", BASE + "/operations/op_1/unknown")).isEmpty();
  }

  @Test
  void tenantAndSupportPrefixedPathsNeverReachLifecycleClassification() {
    // Sanity: the lifecycle routes live only under the internal control base.
    assertThat(policy.classify("POST", "/api/v1/lifecycle/backups")).isEmpty();
    assertThat(policy.classify("POST", "/api/v1/internal/support/lifecycle/backups")).isEmpty();
  }
}
