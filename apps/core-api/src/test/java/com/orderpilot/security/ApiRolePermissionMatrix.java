package com.orderpilot.security;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * OP-CAP-45A — first durable, auditable role → permission matrix foundation.
 *
 * <p>This is an honest <em>foundation</em>, not a full enterprise IAM. It is a deterministic, in-code
 * reference matrix that maps the initial production role profiles onto the existing {@link ApiPermission}
 * grants enforced by {@link ApiRouteSecurityPolicy} / {@link ApiPermissionGuard}. It deliberately does
 * NOT implement an identity provider, OIDC/SSO, or a persisted user-role-permission store — those remain
 * later stages. Today permissions still arrive at the backend through the trusted gateway permission
 * header; this matrix documents and tests which permissions each role is expected to carry, so role
 * allow/deny behaviour is auditable and regression-proof.
 *
 * <p>Kept in test sources on purpose: shipping it as wired production code would falsely imply runtime
 * RBAC enforcement that does not exist yet. The matrix is consumed by {@code ApiPermissionRoleMatrixTest}
 * and is the single auditable source of the role definitions.
 */
final class ApiRolePermissionMatrix {

  enum RoleProfile {
    /** Tenant owner / super-admin: every permission. */
    OWNER_ADMIN,
    /** Sells and approves: quote/review work plus ChangeRequest create/approve/reject — but not execute. */
    SALES_MANAGER,
    /** Day-to-day operator: intake/extraction/validation/review work; no approval or external execution. */
    OPERATOR,
    /** Connector/integration admin: admin settings plus the external-write-adjacent ChangeRequest execute. */
    INTEGRATION_ADMIN,
    /** Read-only governance/audit visibility across the platform; no mutation of any kind. */
    AUDITOR,
    /** Dashboard viewer: analytics/read tiles only. */
    ANALYTICS_VIEWER
  }

  /** Every read-only grant (name ends in READ). An auditor/viewer may only ever hold permissions here. */
  static final Set<ApiPermission> READ_ONLY_PERMISSIONS = EnumSet.copyOf(
      EnumSet.allOf(ApiPermission.class).stream()
          .filter(p -> p.name().endsWith("_READ"))
          .collect(Collectors.toSet()));

  /**
   * External-write-adjacent grant. Reaching connector execution must be limited to the roles that are
   * explicitly trusted to push an approved change to an external system.
   */
  static final ApiPermission EXTERNAL_EXECUTE_PERMISSION = ApiPermission.CHANGE_REQUEST_EXECUTE;

  private static final Map<RoleProfile, Set<ApiPermission>> MATRIX = buildMatrix();

  private ApiRolePermissionMatrix() {}

  static Set<ApiPermission> permissionsFor(RoleProfile role) {
    return MATRIX.get(role);
  }

  /** Comma-joined permission names, as the trusted gateway would present them in the permission header. */
  static String permissionHeaderFor(RoleProfile role) {
    return permissionsFor(role).stream().map(Enum::name).collect(Collectors.joining(","));
  }

  private static Map<RoleProfile, Set<ApiPermission>> buildMatrix() {
    Map<RoleProfile, Set<ApiPermission>> matrix = new EnumMap<>(RoleProfile.class);

    matrix.put(RoleProfile.OWNER_ADMIN, EnumSet.allOf(ApiPermission.class));

    matrix.put(RoleProfile.SALES_MANAGER, EnumSet.of(
        ApiPermission.QUOTE_READ,
        ApiPermission.QUOTE_ACTION,
        ApiPermission.REVIEW_READ,
        ApiPermission.REVIEW_ACTION,
        ApiPermission.INTAKE_READ,
        ApiPermission.ANALYTICS_READ,
        ApiPermission.CHANGE_REQUEST_READ,
        ApiPermission.CHANGE_REQUEST_CREATE,
        ApiPermission.CHANGE_REQUEST_APPROVE,
        ApiPermission.CHANGE_REQUEST_REJECT));

    matrix.put(RoleProfile.OPERATOR, EnumSet.of(
        ApiPermission.INTAKE_READ,
        ApiPermission.INTAKE_WRITE,
        ApiPermission.EXTRACTION_READ,
        ApiPermission.EXTRACTION_RUN,
        ApiPermission.VALIDATION_READ,
        ApiPermission.VALIDATION_RUN,
        ApiPermission.REVIEW_READ,
        ApiPermission.REVIEW_ACTION,
        ApiPermission.QUOTE_READ,
        ApiPermission.BOT_READ,
        ApiPermission.ANALYTICS_READ));

    matrix.put(RoleProfile.INTEGRATION_ADMIN, EnumSet.of(
        ApiPermission.ADMIN_SETTINGS_READ,
        ApiPermission.ADMIN_SETTINGS_MANAGE,
        ApiPermission.CHANNEL_IDENTITY_ACTION,
        ApiPermission.BOT_READ,
        ApiPermission.BOT_ACTION,
        ApiPermission.RUNTIME_ENTITLEMENT_READ,
        ApiPermission.RUNTIME_ENTITLEMENT_MANAGE,
        ApiPermission.CHANGE_REQUEST_READ,
        ApiPermission.CHANGE_REQUEST_CREATE,
        ApiPermission.CHANGE_REQUEST_APPROVE,
        ApiPermission.CHANGE_REQUEST_REJECT,
        ApiPermission.CHANGE_REQUEST_EXECUTE,
        ApiPermission.AUDIT_READ,
        ApiPermission.ANALYTICS_READ));

    // Auditor sees everything readable and may mutate nothing.
    matrix.put(RoleProfile.AUDITOR, EnumSet.copyOf(READ_ONLY_PERMISSIONS));

    matrix.put(RoleProfile.ANALYTICS_VIEWER, EnumSet.of(
        ApiPermission.ANALYTICS_READ,
        ApiPermission.TRUST_ANALYTICS_READ,
        ApiPermission.REVIEW_READ));

    // Freeze: the matrix must be deterministic and unmodifiable once built.
    Map<RoleProfile, Set<ApiPermission>> frozen = new EnumMap<>(RoleProfile.class);
    matrix.forEach((role, perms) -> frozen.put(role, Collections.unmodifiableSet(perms)));
    return Collections.unmodifiableMap(frozen);
  }
}
