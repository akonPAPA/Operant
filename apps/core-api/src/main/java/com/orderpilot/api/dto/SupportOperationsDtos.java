package com.orderpilot.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OP-CAP-55 — response contracts for the internal owner-company support <b>operations visibility</b> surface
 * (read-only). This stage adds NO new mutation and NO new executor: it only aggregates safe, backend-owned
 * counts / lifecycle markers from the existing support, incident, break-glass, and data-repair records so
 * Operant-owner support staff can inspect operations state.
 *
 * <p>Contract law (proven by {@code SupportOperationsContractTest}):
 * <ul>
 *   <li>Every field is an operator-safe scalar/enum/id/timestamp. There is NO secret, credential, token,
 *       connector config, raw webhook/document/AI payload, raw SQL/script, or internal stack trace.</li>
 *   <li>No raw audit-table internals are exposed — lifecycle markers are derived from the domain records'
 *       own backend-owned timestamps/status, not from audit rows.</li>
 *   <li>No actor/approver internal id is exposed.</li>
 *   <li>Everything is tenant-scoped; the service never returns another tenant's records.</li>
 * </ul>
 */
public final class SupportOperationsDtos {
  private SupportOperationsDtos() {}

  /**
   * OP-CAP-55 — safe operations summary for one tenant: bounded counts plus the latest safe activity
   * timestamp. Counts are backend-computed via tenant-scoped count queries; no raw record content is
   * exposed.
   */
  public record SupportOperationsSummaryResponse(
      UUID tenantId,
      long openIncidents,
      long criticalOpenIncidents,
      long pendingBreakGlassRequests,
      long approvedActiveBreakGlassRequests,
      long pendingSupportGrants,
      long activeSupportGrants,
      long pendingDataRepairApprovals,
      long approvedDataRepairRequests,
      long executedProcessingJobRepairs,
      long rejectedDataRepairRequests,
      Instant latestActivityAt,
      Instant generatedAt,
      String externalExecution) {}

  /**
   * OP-CAP-55 — a single safe lifecycle marker in the operations timeline. It carries only a bounded
   * category/event enum, the support-domain record id it refers to, that record's current safe status, and
   * the backend-owned timestamp the event occurred at. It never carries free-form reason text, a raw
   * payload, a secret, or an actor id.
   */
  public record SupportOperationsTimelineEntry(
      String category,
      String eventType,
      UUID referenceId,
      String status,
      Instant occurredAt) {}

  /**
   * OP-CAP-55 — a bounded, deterministically time-ordered (desc) page of operations timeline markers. The
   * page is always bounded (capped page size, capped scan window); it never streams an unbounded full table.
   */
  public record SupportOperationsTimelineResponse(
      UUID tenantId,
      int page,
      int pageSize,
      int returnedCount,
      boolean hasMore,
      List<SupportOperationsTimelineEntry> entries,
      Instant generatedAt) {}

  /**
   * OP-CAP-55 — operations-oriented detail view of one data-repair request (and its bounded processing-job
   * repair execution result, if the target is {@code PROCESSING_JOB_STATUS_REPAIR}). Exposes only
   * backend-owned safe fields: lifecycle status, the backend-built affected-target summary, the safe
   * execution-result metadata, and a derived per-request safe lifecycle timeline. It exposes NO
   * requester/approver/executor actor id, NO raw reason payload, NO SQL/script, and NO cross-tenant data.
   */
  public record DataRepairOperationsViewResponse(
      UUID requestId,
      UUID tenantId,
      String targetType,
      String approvalStatus,
      String executionStatus,
      String dryRunSummary,
      String affectedTargetSummary,
      UUID processingJobId,
      String previousStatus,
      String newStatus,
      Instant executedAt,
      boolean executed,
      List<SupportOperationsTimelineEntry> timeline,
      Instant generatedAt,
      String externalExecution) {}
}
