package com.orderpilot.domain.support;

import java.util.Set;

public enum StaffRole {
  /** Read-only support: diagnostics observation only. Cannot record maintenance or request data repair. */
  SUPPORT_VIEWER(Set.of(StaffSupportScope.DIAGNOSTICS)),
  /** Maintenance engineer: diagnostics + maintenance/update audit records. No data repair. */
  MAINTENANCE_ENGINEER(Set.of(StaffSupportScope.DIAGNOSTICS, StaffSupportScope.MAINTENANCE)),
  /** Support engineer: full bounded support surface (diagnostics, maintenance records, data-repair dry-run). */
  SUPPORT_ENGINEER(Set.of(StaffSupportScope.DIAGNOSTICS, StaffSupportScope.MAINTENANCE, StaffSupportScope.DATA_REPAIR));

  private final Set<StaffSupportScope> allowedScopes;

  StaffRole(Set<StaffSupportScope> allowedScopes) {
    this.allowedScopes = allowedScopes;
  }

  /** Whether this role may ever hold a grant for the given scope. */
  public boolean permits(StaffSupportScope scope) {
    return scope != null && allowedScopes.contains(scope);
  }

  public Set<StaffSupportScope> allowedScopes() {
    return allowedScopes;
  }
}
