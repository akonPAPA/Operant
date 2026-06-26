package com.orderpilot.domain.incident;

/**
 * OP-CAP-53 — bounded incident classification. This is a closed allowlist (no free-form type) so an
 * incident can never carry an arbitrary/executable category label.
 */
public enum IncidentType {
  SECURITY_INCIDENT,
  PRODUCTION_OUTAGE,
  DATA_INTEGRITY_RISK,
  CONNECTOR_ABUSE,
  TENANT_ACCESS_ISSUE,
  MIGRATION_FAILURE,
  SUPPORT_ESCALATION
}
