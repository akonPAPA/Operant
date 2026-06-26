package com.orderpilot.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-53 — request/response contracts for the internal incident-response / break-glass surface.
 *
 * <p>Contract law (enforced by the service, proven by tests):
 * <ul>
 *   <li>Request DTOs carry <b>business intent only</b>. They never accept a tenant id, an acting/approving
 *       staff actor, a status, an expiry, an approval/decision state, or audit metadata — the backend owns
 *       all of those. The target tenant comes from the trusted {@code X-Tenant-Id} context and the acting
 *       staff actor from the trusted actor header; the incident/request ids come from the path.</li>
 *   <li>There is no free-form SQL / script / command / raw-target / connector / secret field anywhere.</li>
 *   <li>Response DTOs expose only operator-safe fields: no actor ids, no secrets, no connector credentials,
 *       no raw tokens/payloads, no audit internals, no cross-tenant data.</li>
 * </ul>
 */
public final class IncidentInternalDtos {
  private IncidentInternalDtos() {}

  // --- incidents ---

  /** Open an incident. Backend owns status/timestamps/createdByStaffActor and the tenant scope. */
  public record CreateIncidentRequest(
      String title,
      String reason,
      String incidentType,
      String severity) {}

  /** Close an incident. A critical incident requires a non-blank closure reason. */
  public record CloseIncidentRequest(String closureReason) {}

  public record IncidentResponse(
      UUID incidentId,
      UUID tenantId,
      String title,
      String reason,
      String severity,
      String incidentType,
      String status,
      Instant createdAt,
      Instant updatedAt,
      Instant closedAt,
      String closureReason) {}

  // --- break-glass access requests ---

  /** Request emergency break-glass access against an incident. Backend owns status/expiry/actor; bounded TTL. */
  public record CreateBreakGlassRequest(
      String scope,
      String reason,
      Long ttlSeconds) {}

  /** An approver's break-glass approval/rejection/revocation note — business intent only. */
  public record BreakGlassDecisionRequest(String note) {}

  public record BreakGlassResponse(
      UUID requestId,
      UUID tenantId,
      UUID incidentId,
      String scope,
      String status,
      String reason,
      Instant requestedAt,
      Instant decidedAt,
      Instant expiresAt,
      Instant revokedAt) {}
}
