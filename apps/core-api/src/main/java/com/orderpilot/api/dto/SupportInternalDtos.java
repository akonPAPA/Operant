package com.orderpilot.api.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * OP-CAP-51 — request/response contracts for the internal owner-company support/maintenance surface.
 *
 * <p>Contract law (enforced by the services, proven by tests):
 * <ul>
 *   <li>Request DTOs carry <b>business intent only</b>. They never accept tenant id, acting staff id,
 *       status, expiry, approval state, execution authority, or audit metadata — the backend owns all of
 *       those. The target tenant comes from the trusted {@code X-Tenant-Id} context and the acting staff
 *       actor from the trusted actor header.</li>
 *   <li>Response DTOs expose only operator-safe fields: no secrets, no connector credentials, no raw
 *       webhook/document/AI payloads, no internal stack traces.</li>
 *   <li>There is no free-form SQL / script / raw-target field anywhere in this surface.</li>
 * </ul>
 */
public final class SupportInternalDtos {
  private SupportInternalDtos() {}

  // --- support access grants ---

  /** Create a scoped, reasoned, expiring support access grant. Backend owns status/expiry/createdBy. */
  public record CreateSupportAccessGrantRequest(
      UUID granteeStaffUserId,
      String scope,
      String supportCaseRef,
      Long ttlSeconds) {}

  public record SupportAccessGrantResponse(
      UUID grantId,
      UUID tenantId,
      String scope,
      String status,
      String approvalStatus,
      String supportCaseRef,
      Instant expiresAt,
      Instant createdAt) {}

  /**
   * OP-CAP-52 — an approver's grant approval/rejection decision. Business intent only: an optional decision
   * note. The acting approver, the decision status, and the approval timestamps are all backend-owned — the
   * body can never carry approvedBy/rejectedBy/approvalStatus.
   */
  public record SupportGrantApprovalDecisionRequest(String decisionNote) {}

  // --- tenant diagnostics (read-only, redacted) ---

  public record SupportTenantDiagnosticsResponse(
      UUID tenantId,
      String health,
      Map<String, Long> jobStatusCounts,
      long totalJobs,
      Instant lastJobActivityAt,
      Instant generatedAt,
      String externalExecution,
      String scope) {}

  // --- maintenance/update audit records ---

  public record MaintenanceActionRecordRequest(
      String actionType,
      String reason,
      String targetScope) {}

  public record MaintenanceActionRecordResponse(
      UUID recordId,
      UUID tenantId,
      String actionType,
      String status,
      String targetScope,
      Instant createdAt) {}

  // --- data-repair dry-run requests ---

  public record DataRepairDryRunRequest(
      String targetType,
      String reason,
      String note) {}

  public record DataRepairDryRunResponse(
      UUID requestId,
      UUID tenantId,
      String targetType,
      String status,
      String executionStatus,
      String summary,
      Instant createdAt) {}

  // --- data-repair approval workflow (OP-CAP-52) ---

  /**
   * OP-CAP-52 — request execution approval for an existing data-repair request. Business intent only: an
   * operator-safe affected-target summary, an optional note, and an optional support/incident reference.
   * It never carries SQL/script/table/connector/secret/authority — the affected-target summary is free text
   * describing intent, not an executable target. The requester actor and tenant are backend-resolved.
   */
  public record DataRepairApprovalRequest(
      String affectedTargetSummary,
      String note,
      String supportCaseRef) {}

  /** OP-CAP-52 — an approver's data-repair approval/rejection decision. Optional decision note only. */
  public record DataRepairApprovalDecisionRequest(String decisionNote) {}

  /**
   * OP-CAP-52 — operator-safe view of a data-repair request through its approval lifecycle. Exposes only
   * backend-owned safe fields: no requester/approver id, no audit/internal ids, no raw target, no SQL.
   */
  public record DataRepairRequestResponse(
      UUID requestId,
      UUID tenantId,
      String targetType,
      String status,
      String approvalStatus,
      String executionStatus,
      String affectedTargetSummary,
      Instant approvalExpiresAt,
      String message,
      Instant createdAt) {}

  // --- OP-CAP-54: bounded processing-job status-repair execution ---

  /**
   * OP-CAP-54 — the execute command for the one bounded repair target {@code PROCESSING_JOB_STATUS_REPAIR}.
   * It carries BUSINESS/SAFE INTENT ONLY: which processing job, the operator's expected current status, the
   * desired bounded status, and an audit reason. {@code expectedCurrentStatus}/{@code desiredStatus} are
   * operational intent (matched against the existing {@code ProcessingJobStatus} allowlist by the backend
   * validator) — NOT authority over the request's lifecycle.
   *
   * <p>It deliberately carries NO request-lifecycle authority and NO executable payload: no tenant id, no
   * actor/approver id, no approvalStatus/executionStatus, no raw SQL/script, no tableName/fieldName, no
   * arbitrary JSON patch, no connector credential/secret. The tenant is the trusted {@code X-Tenant-Id}
   * context, the actor is the trusted actor header, and approval lives on the backend-owned
   * {@code DataRepairRequest}.
   */
  public record ProcessingJobRepairExecuteRequest(
      UUID processingJobId,
      String expectedCurrentStatus,
      String desiredStatus,
      String reason) {}

  /**
   * OP-CAP-54 — operator-safe result of a processing-job status repair (or its idempotent replay). Exposes
   * only backend-owned safe operational fields: no actor/approver id, no secret, no raw payload, no
   * internal stack trace, no cross-tenant data.
   */
  public record ProcessingJobRepairResponse(
      UUID requestId,
      String targetType,
      UUID targetId,
      String executionStatus,
      String previousStatus,
      String newStatus,
      Instant executedAt,
      String message) {}
}
