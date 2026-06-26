package com.orderpilot.domain.support;

import java.util.Set;

/**
 * OP-CAP-51 — internal staff role profiles for the owner-company support/maintenance bridge. A role
 * declares the closed set of {@link StaffSupportScope} capabilities a staff principal may ever be granted.
 * It does NOT grant tenant access by itself: an actual {@link SupportAccessGrant} (scoped, reasoned, and
 * expiring) is still required per tenant. Roles are intentionally coarse — this is a bounded foundation,
 * not a full IAM.
 */
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
