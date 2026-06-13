package com.orderpilot.domain.usage;

/**
 * OP-CAP-16E Persistent Tenant Entitlements — lifecycle status of a tenant's runtime plan. Only
 * {@link #ACTIVE} (within its effective window) grants feature access; the others deny.
 */
public enum TenantRuntimePlanStatus {
  ACTIVE,
  SUSPENDED,
  EXPIRED,
  DISABLED
}
