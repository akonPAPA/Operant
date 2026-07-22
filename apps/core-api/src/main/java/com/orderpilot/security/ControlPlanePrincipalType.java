package com.orderpilot.security;

/**
 * Server-owned control-plane principal classes. These classes are intentionally disjoint from tenant,
 * external-customer, and browser-gateway identities.
 */
public enum ControlPlanePrincipalType {
  /** Human Operant support, maintenance, SRE, security, or release operator. */
  OPERANT_STAFF("STAFF_CONTROL_"),
  /** Non-browser machine principal that leases and reports lifecycle work. */
  LIFECYCLE_EXECUTOR("CONTROL_EXECUTOR_");

  private final String permissionPrefix;

  ControlPlanePrincipalType(String permissionPrefix) {
    this.permissionPrefix = permissionPrefix;
  }

  public boolean permits(ApiPermission permission) {
    return permission != null && permission.name().startsWith(permissionPrefix);
  }

  String permissionPrefix() {
    return permissionPrefix;
  }
}
