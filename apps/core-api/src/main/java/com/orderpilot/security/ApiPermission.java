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
  STAFF_DATA_REPAIR_DRYRUN,
  // OP-CAP-52: approve/reject a sensitive support access grant. Separate from STAFF_SUPPORT_GRANT_MANAGE
  // (which creates/revokes) so the actor who mints a grant request cannot also approve it from the same
  // grant — a sensitive grant stays non-usable until an approver with THIS permission approves it.
  STAFF_SUPPORT_GRANT_APPROVE,
  // OP-CAP-52: approve/reject a data-repair request for execution. Separate from STAFF_DATA_REPAIR_DRYRUN
  // (the requester tier) so a data-repair requester cannot self-approve their own request from the dry-run
  // grant alone.
  STAFF_DATA_REPAIR_APPROVE,
  // OP-CAP-52: attempt to execute an approved data-repair request. This permission only gates the
  // execution STUB — real execution is disabled in this stage and the endpoint always fails closed
  // (denied without an approval, execution-disabled even with one). It can never write a business row.
  STAFF_DATA_REPAIR_EXECUTION_ATTEMPT,
  // OP-CAP-53: internal owner-company incident-response / break-glass staff permissions. Like the
  // OP-CAP-51/52 STAFF_SUPPORT_* family, these gate the /api/v1/internal/support surface and are SEPARATE
  // from every tenant customer/operator business permission — no tenant role profile holds them (proven by
  // ApiRolePermissionMatrix, which excludes the whole STAFF_* family). Each break-glass verb maps to a
  // distinct permission so a requester cannot self-approve and an approver cannot mint a request.
  // Create/read/close an incident record.
  STAFF_INCIDENT_CREATE,
  STAFF_INCIDENT_READ,
  STAFF_INCIDENT_CLOSE,
  // Request emergency break-glass access against an incident (the weakest break-glass tier).
  STAFF_BREAK_GLASS_REQUEST,
  // Approve/reject a break-glass request — a separate, stronger authority than requesting it, so the
  // requester can never self-approve from the request permission.
  STAFF_BREAK_GLASS_APPROVE,
  // Revoke a break-glass grant — a dedicated emergency-containment authority.
  STAFF_BREAK_GLASS_REVOKE,
  // OP-CAP-54: execute the FIRST real, bounded data-repair — an approved, deterministically-validated
  // processing-job status repair. It is DISTINCT from and STRONGER than STAFF_DATA_REPAIR_EXECUTION_ATTEMPT
  // (which only ever reaches the disabled generic stub): this permission gates the one endpoint that can
  // actually mutate a processing_job row, and only for the bounded PROCESSING_JOB_STATUS_REPAIR target.
  // Like the rest of the STAFF_* family it is never held by any tenant role (ApiRolePermissionMatrix
  // excludes the whole STAFF_* prefix), and it can never reach a business order/quote/inventory/customer/
  // price table or any connector/ERP write.
  STAFF_PROCESSING_JOB_REPAIR_EXECUTE,
  // P1-E: bounded platform control-plane reads under /api/v1/internal/control. These gate the
  // deployment-operator surface consumed by operantctl and are SEPARATE from the per-tenant
  // STAFF_SUPPORT_* diagnostics family: a support-read grant must not see platform control state and a
  // control-read grant must not see tenant support data. Like the whole STAFF_* family, no tenant role
  // holds them.
  // Read bounded platform status/health/readiness (no config values, hosts, paths, or secrets).
  STAFF_CONTROL_READ,
  // Read the bounded, redacted platform diagnostics — a stronger read tier than STAFF_CONTROL_READ
  // because diagnostics reveal dependency/migration detail; a control-read grant alone is denied.
  STAFF_CONTROL_DIAGNOSE,
  // P1-E lifecycle (operational-event slice): read the bounded, server-owned TYPED operational-event
  // projection (dependency/readiness state changes and future lifecycle-operation events) under
  // /api/v1/internal/control/operational-events. A DEDICATED permission, never implied by
  // STAFF_CONTROL_READ or STAFF_CONTROL_DIAGNOSE, and deliberately kept in the STAFF_CONTROL_ family so
  // the merged control-credential validator accepts it and the role matrix keeps it out of every tenant
  // role (STAFF_* is excluded by prefix). It attributes to the interactive Operant support/maintenance
  // control principal - a future non-human deployment automation slice would introduce a distinct
  // SERVICE_CONTROL_* permission with its own SERVICE_ACCOUNT principal, not this one.
  STAFF_CONTROL_OPERATIONAL_EVENT_READ,
  // P1-E2A durable backup operation control slice. Two DISJOINT control-plane principal classes gate the
  // bounded lifecycle surface under /api/v1/internal/control/lifecycle:
  //   * the STAFF_CONTROL_* family below is held by the human Operant support/maintenance control
  //     principal (operantctl): it may READ lifecycle operations and REQUEST a backup, but may NOT lease
  //     or complete operations;
  //   * the CONTROL_EXECUTOR_* family is held ONLY by a dedicated lifecycle executor principal: it may
  //     lease and complete already-authorized operations, but may NOT request or read the staff surface.
  // The control-credential validator enforces that a single credential holds AT MOST ONE of these two
  // families (mutual exclusion), so a staff credential can never act as an executor and vice versa. Like
  // the whole STAFF_* family, the staff-control permissions are excluded from every tenant role; the
  // CONTROL_EXECUTOR_* family is likewise excluded from every tenant role by ApiRolePermissionMatrix.
  // Read bounded lifecycle operations (state/type/result only; no fencing token, hash, or fingerprint).
  STAFF_CONTROL_LIFECYCLE_READ,
  // Request a durable backup operation. Fixed BACKUP type; no client-chosen path/database/container.
  STAFF_CONTROL_BACKUP,
  // Executor-only: atomically lease the next authorized operation and receive a fencing token.
  CONTROL_EXECUTOR_LEASE,
  // Executor-only: report a bounded terminal result for an operation the executor currently holds.
  CONTROL_EXECUTOR_REPORT
}
