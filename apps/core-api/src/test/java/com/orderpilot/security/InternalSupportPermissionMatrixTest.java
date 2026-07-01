package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.security.ApiRouteSecurityPolicy.RouteDecision;
import com.orderpilot.security.ApiRouteSecurityPolicy.SecurityClassification;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

class InternalSupportPermissionMatrixTest {
  private static final String BASE = "/api/v1/internal/support";
  private static final String TENANT = "123e4567-e89b-12d3-a456-426614174000";
  private static final String INCIDENT = "123e4567-e89b-12d3-a456-426614174111";
  private static final String REQUEST = "123e4567-e89b-12d3-a456-426614174222";
  private static final String GRANT = "123e4567-e89b-12d3-a456-426614174333";

  private final ApiRouteSecurityPolicy policy = new ApiRouteSecurityPolicy();

  @ParameterizedTest(name = "{0} {1} -> {3}")
  @MethodSource("internalSupportRoutes")
  void everyImplementedInternalSupportRouteRequiresItsDedicatedStaffPermission(
      String method, String path, SecurityClassification classification, ApiPermission permission) {
    RouteDecision decision = policy.classify(method, path).orElseThrow();

    assertThat(decision.classification()).isEqualTo(classification);
    assertThat(decision.requiredPermission()).isEqualTo(permission);
    assertThat(decision.requiredPermission().name()).startsWith("STAFF_");
    assertThat(decision.isPublic()).isFalse();
  }

  @Test
  void normalTenantBusinessRouteKeepsTenantPermission() {
    RouteDecision read = policy.classify("GET", "/api/v1/analytics/overview").orElseThrow();

    assertThat(read.requiredPermission()).isEqualTo(ApiPermission.ANALYTICS_READ);
    assertThat(read.requiredPermission().name()).doesNotStartWith("STAFF_");
  }

  private static Stream<org.junit.jupiter.params.provider.Arguments> internalSupportRoutes() {
    return Stream.of(
        route("POST", BASE + "/access-grants", SecurityClassification.PROTECTED_CREATE,
            ApiPermission.STAFF_SUPPORT_GRANT_MANAGE),
        route("POST", BASE + "/access-grants/" + GRANT + "/revoke", SecurityClassification.PROTECTED_CREATE,
            ApiPermission.STAFF_SUPPORT_GRANT_MANAGE),
        route("POST", BASE + "/access-grants/" + GRANT + "/approve", SecurityClassification.PROTECTED_APPROVE,
            ApiPermission.STAFF_SUPPORT_GRANT_APPROVE),
        route("POST", BASE + "/access-grants/" + GRANT + "/reject", SecurityClassification.PROTECTED_REJECT,
            ApiPermission.STAFF_SUPPORT_GRANT_APPROVE),
        route("GET", BASE + "/tenants/" + TENANT + "/access-grants", SecurityClassification.PROTECTED_READ,
            ApiPermission.STAFF_SUPPORT_READ),
        route("GET", BASE + "/tenants/" + TENANT + "/diagnostics", SecurityClassification.PROTECTED_READ,
            ApiPermission.STAFF_SUPPORT_READ),
        route("GET", BASE + "/tenants/search", SecurityClassification.PROTECTED_READ,
            ApiPermission.STAFF_SUPPORT_READ),
        route("GET", BASE + "/tenants/" + TENANT + "/support-context", SecurityClassification.PROTECTED_READ,
            ApiPermission.STAFF_SUPPORT_READ),
        route("GET", BASE + "/tenants/" + TENANT + "/operations/summary", SecurityClassification.PROTECTED_READ,
            ApiPermission.STAFF_SUPPORT_READ),
        route("GET", BASE + "/tenants/" + TENANT + "/operations/timeline", SecurityClassification.PROTECTED_READ,
            ApiPermission.STAFF_SUPPORT_READ),
        route("GET", BASE + "/tenants/" + TENANT + "/data-repair-requests/" + REQUEST + "/operations-view",
            SecurityClassification.PROTECTED_READ, ApiPermission.STAFF_SUPPORT_READ),
        route("POST", BASE + "/tenants/" + TENANT + "/maintenance-records",
            SecurityClassification.PROTECTED_CREATE, ApiPermission.STAFF_MAINTENANCE_RECORD),
        route("POST", BASE + "/tenants/" + TENANT + "/data-repair-requests/dry-run",
            SecurityClassification.PROTECTED_CREATE, ApiPermission.STAFF_DATA_REPAIR_DRYRUN),
        route("POST", BASE + "/tenants/" + TENANT + "/data-repair-requests/" + REQUEST + "/request-approval",
            SecurityClassification.PROTECTED_CREATE, ApiPermission.STAFF_DATA_REPAIR_DRYRUN),
        route("POST", BASE + "/tenants/" + TENANT + "/data-repair-requests/" + REQUEST + "/approve",
            SecurityClassification.PROTECTED_APPROVE, ApiPermission.STAFF_DATA_REPAIR_APPROVE),
        route("POST", BASE + "/tenants/" + TENANT + "/data-repair-requests/" + REQUEST + "/reject",
            SecurityClassification.PROTECTED_REJECT, ApiPermission.STAFF_DATA_REPAIR_APPROVE),
        route("POST", BASE + "/tenants/" + TENANT + "/data-repair-requests/" + REQUEST + "/execute",
            SecurityClassification.PROTECTED_EXECUTE, ApiPermission.STAFF_DATA_REPAIR_EXECUTION_ATTEMPT),
        route("POST",
            BASE + "/tenants/" + TENANT + "/data-repair-requests/" + REQUEST + "/execute-processing-job-repair",
            SecurityClassification.PROTECTED_EXECUTE, ApiPermission.STAFF_PROCESSING_JOB_REPAIR_EXECUTE),
        route("POST", BASE + "/incidents", SecurityClassification.PROTECTED_CREATE,
            ApiPermission.STAFF_INCIDENT_CREATE),
        route("GET", BASE + "/incidents/" + INCIDENT, SecurityClassification.PROTECTED_READ,
            ApiPermission.STAFF_INCIDENT_READ),
        route("POST", BASE + "/incidents/" + INCIDENT + "/close", SecurityClassification.PROTECTED_UPDATE,
            ApiPermission.STAFF_INCIDENT_CLOSE),
        route("POST", BASE + "/tenants/" + TENANT + "/incidents/" + INCIDENT + "/break-glass-requests",
            SecurityClassification.PROTECTED_CREATE, ApiPermission.STAFF_BREAK_GLASS_REQUEST),
        route("POST", BASE + "/tenants/" + TENANT + "/break-glass-requests/" + REQUEST + "/approve",
            SecurityClassification.PROTECTED_APPROVE, ApiPermission.STAFF_BREAK_GLASS_APPROVE),
        route("POST", BASE + "/tenants/" + TENANT + "/break-glass-requests/" + REQUEST + "/reject",
            SecurityClassification.PROTECTED_REJECT, ApiPermission.STAFF_BREAK_GLASS_APPROVE),
        route("POST", BASE + "/tenants/" + TENANT + "/break-glass-requests/" + REQUEST + "/revoke",
            SecurityClassification.PROTECTED_UPDATE, ApiPermission.STAFF_BREAK_GLASS_REVOKE));
  }

  private static org.junit.jupiter.params.provider.Arguments route(
      String method, String path, SecurityClassification classification, ApiPermission permission) {
    return org.junit.jupiter.params.provider.Arguments.of(method, path, classification, permission);
  }
}
