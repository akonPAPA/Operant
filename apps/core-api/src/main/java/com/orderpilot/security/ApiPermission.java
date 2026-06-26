package com.orderpilot.security;

public enum ApiPermission {
  ANALYTICS_READ,
  ANALYTICS_MANAGE,
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
  ADMIN_SETTINGS_MANAGE,
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
  TRUST_AI_RUNTIME_TRACE_WRITE,
  // OP-CAP-18: read trust/AI domain events and projection checkpoints/dead-letter.
  TRUST_AI_EVENT_READ,
  // OP-CAP-18: trigger projector processing of trust/AI events — stronger than read.
  TRUST_AI_EVENT_PROCESS,
  // OP-CAP-18: read operator correction learning records.
  TRUST_OPERATOR_CORRECTION_READ,
  // OP-CAP-18: record an operator correction learning record.
  TRUST_OPERATOR_CORRECTION_WRITE,
  // OP-CAP-18: approve an operator correction for governed AI-memory learning (gate to HUMAN_APPROVED).
  TRUST_OPERATOR_CORRECTION_APPROVE,
  // OP-CAP-18: reject an operator correction for learning.
  TRUST_OPERATOR_CORRECTION_REJECT,
  // OP-CAP-19: read advisory-memory evaluation runs/cases/results.
  TRUST_AI_MEMORY_EVALUATION_READ,
  // OP-CAP-19: create evaluation runs/cases — stronger than read.
  TRUST_AI_MEMORY_EVALUATION_WRITE,
  // OP-CAP-19: execute an evaluation run — the strongest evaluation permission. Generic AI-memory
  // read/write never grants it.
  TRUST_AI_MEMORY_EVALUATION_RUN,
  CHANGE_REQUEST_READ,
  CHANGE_REQUEST_CREATE,
  CHANGE_REQUEST_APPROVE,
  CHANGE_REQUEST_REJECT,
  CHANGE_REQUEST_EXECUTE,
  // OP-CAP-51: internal owner-company staff/support permissions. These gate the /api/v1/internal/support
  // surface and are deliberately SEPARATE from every tenant customer/operator business permission above —
  // a tenant operator/demo permission header never carries them, and no tenant role profile holds them
  // (see ApiRolePermissionMatrix). They are the route-edge (first) layer; a scoped, reasoned, expiring
  // SupportAccessGrant validated in the service is the second layer.
  // Read-only support: tenant diagnostics + support grant/registry reads.
  STAFF_SUPPORT_READ,
  // Create/revoke support access grants (support-admin action).
  STAFF_SUPPORT_GRANT_MANAGE,
  // Record a maintenance/update action (audit/record only — never triggers execution).
  STAFF_MAINTENANCE_RECORD,
  // Request a controlled data-repair dry-run (execution remains disabled in this stage).
  STAFF_DATA_REPAIR_DRYRUN
}
