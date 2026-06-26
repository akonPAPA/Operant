package com.orderpilot.domain.support;

/**
 * OP-CAP-51 — typed internal staff/support capability set. This is the second-layer (service)
 * authorization concept, deliberately separate from the route-edge {@code ApiPermission} family and from
 * tenant customer/operator business permissions.
 *
 * <p>A {@link SupportAccessGrant} authorizes exactly one staff principal to perform exactly one of these
 * scopes against exactly one tenant, and only until the grant expires. A {@link StaffRole} declares which
 * scopes a staff principal may ever be granted. Nothing here implies a permanent or all-tenant capability.
 */
public enum StaffSupportScope {
  /** Read-only safe tenant/runtime diagnostics. Never raw payloads, secrets, or business mutation. */
  DIAGNOSTICS,
  /** Record a maintenance/update action (audit/record only — never triggers execution). */
  MAINTENANCE,
  /** Prepare a data-repair request as a dry-run only. Execution remains disabled in this stage. */
  DATA_REPAIR;

  /**
   * OP-CAP-52 — whether this scope is low-risk enough to activate a grant immediately without an explicit
   * human approval step. Only read-only {@link #DIAGNOSTICS} qualifies; every scope that can record or
   * change-adjacent (maintenance, data repair) is sensitive and a grant for it stays non-usable until an
   * approver with {@code STAFF_SUPPORT_GRANT_APPROVE} approves it.
   */
  public boolean isLowRiskAutoApprovable() {
    return this == DIAGNOSTICS;
  }
}
