package com.orderpilot.domain.usage;

/**
 * OP-CAP-16E Persistent Tenant Entitlements — the runtime plan a tenant is assigned. This is a
 * governance/packaging label only; it carries no price and no billing meaning in this stage.
 */
public enum TenantRuntimePlanCode {
  FREE,
  PILOT,
  PRO,
  ENTERPRISE,
  CUSTOM
}
