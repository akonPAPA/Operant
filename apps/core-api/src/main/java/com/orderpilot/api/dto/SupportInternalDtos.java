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
      String supportCaseRef,
      Instant expiresAt,
      Instant createdAt) {}

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
}
