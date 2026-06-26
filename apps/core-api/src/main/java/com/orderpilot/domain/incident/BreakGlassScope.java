package com.orderpilot.domain.incident;

/**
 * OP-CAP-53 — bounded break-glass scope labels. These are <b>policy labels only</b> in this stage: a valid,
 * approved break-glass grant authorizes the labelled emergency activity conceptually, but it executes NO
 * business mutation, runs NO SQL/script, and calls NO connector/ERP by itself. A scope narrows what a future
 * emergency action could be, never broadens it, and is matched exactly at authorization time.
 */
public enum BreakGlassScope {
  /** Read emergency incident diagnostics for the tenant. */
  INCIDENT_DIAGNOSTICS,
  /** Override-approve a support access grant during an incident (policy label only — no auto-approval here). */
  SUPPORT_GRANT_OVERRIDE_APPROVAL,
  /** Emergency-approve a data-repair request during an incident (policy label only — execution stays disabled). */
  DATA_REPAIR_EMERGENCY_APPROVAL,
  /** Freeze a connector during an incident (policy label only — no connector write in this stage). */
  CONNECTOR_FREEZE,
  /** Freeze tenant access during an incident (policy label only — no business mutation in this stage). */
  TENANT_ACCESS_FREEZE,
  /** Review a security event during an incident. */
  SECURITY_EVENT_REVIEW
}
