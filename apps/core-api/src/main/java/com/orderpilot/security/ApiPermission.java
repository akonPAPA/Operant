package com.orderpilot.security;

public enum ApiPermission {
  ANALYTICS_READ,
  INTAKE_READ,
  INTAKE_WRITE,
  EXTRACTION_READ,
  EXTRACTION_RUN,
  VALIDATION_READ,
  VALIDATION_RUN,
  REVIEW_READ,
  REVIEW_ACTION,
  QUOTE_READ,
  QUOTE_ACTION,
  BOT_READ,
  BOT_ACTION,
  AUDIT_READ,
  ADMIN_SETTINGS_READ,
  CHANNEL_IDENTITY_ACTION,
  AI_WORK_ACTION,
  // OP-CAP-07D: internal/service permission for the AI-worker result intake endpoint.
  AI_RESULT_INTAKE,
  // OP-CAP-16I: platform/admin runtime governance — read tenant plan/feature entitlement status.
  RUNTIME_ENTITLEMENT_READ,
  // OP-CAP-16I: platform/admin runtime governance — create/update plans and feature entitlements.
  // Not for general operators.
  RUNTIME_ENTITLEMENT_MANAGE,
  // OP-CAP-17A: read-only access to deterministic document trust runs/signals.
  TRUST_READ,
  // OP-CAP-17D: evaluate a deterministic trust risk decision (write-through the backend engine).
  TRUST_RISK_EVALUATE,
  // OP-CAP-17D: manually override a trust risk decision — stronger than evaluate. Not for general
  // operators; a CRITICAL decision can never be silently downgraded.
  TRUST_RISK_OVERRIDE,
  // OP-CAP-17E: read-only access to derived trust analytics read models (review queue, counterparty
  // dashboard, outstanding debt, document anomaly trends, risk distribution).
  TRUST_ANALYTICS_READ,
  // OP-CAP-17E: trigger a bounded tenant rebuild of the trust analytics read models — stronger than
  // read. Not for general operators (an admin/maintenance action).
  TRUST_ANALYTICS_REBUILD,
  // OP-CAP-17F: read-only access to tenant-scoped advisory AI memory records/evidence/invalidations.
  TRUST_AI_MEMORY_READ,
  // OP-CAP-17F: create/supersede AI memory records — stronger than read. Generic TRUST_READ never grants it.
  TRUST_AI_MEMORY_WRITE,
  // OP-CAP-17F: invalidate an AI memory record — a dedicated governance action.
  TRUST_AI_MEMORY_INVALIDATE,
  // OP-CAP-17F: read AI runtime trace metadata.
  TRUST_AI_RUNTIME_TRACE_READ,
  // OP-CAP-17F: record AI runtime trace metadata — a narrow write boundary.
  TRUST_AI_RUNTIME_TRACE_WRITE
}
