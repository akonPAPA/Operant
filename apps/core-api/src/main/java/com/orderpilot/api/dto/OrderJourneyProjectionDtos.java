package com.orderpilot.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OP-CAP-23 — bounded, tenant-safe DTOs for the Order Journey projector runtime control + observability
 * surface. No raw payloads, secrets, or payment-sensitive data are exposed; failure messages are bounded by
 * the persistence layer.
 */
public final class OrderJourneyProjectionDtos {
  private OrderJourneyProjectionDtos() {}

  /** Tally returned by a bounded projector batch run. */
  public record ProcessJourneyProjectionResponse(
      int fetched,
      int processed,
      int skipped,
      int failed,
      int deadLettered,
      Instant generatedAt) {}

  /**
   * Bounded projector health snapshot (cheap tenant-scoped counts + recent failures). OP-CAP-25 adds
   * {@code oldestPendingAt} (the occurredAt of this tenant's oldest drainable event, for staleness
   * monitoring), {@code schedulerEnabled} (whether the controlled scheduled drain is configured on), and
   * {@code configuredBatchSize} (the clamped per-tenant drain batch). No tenant-sensitive data is exposed.
   */
  public record JourneyProjectionHealthDto(
      long pendingEvents,
      long failedEvents,
      long deadLetteredEvents,
      long failedCheckpoints,
      Instant lastProcessedAt,
      List<JourneyProjectionFailureDto> recentFailures,
      Instant oldestPendingAt,
      boolean schedulerEnabled,
      int configuredBatchSize,
      Instant generatedAt) {}

  /**
   * OP-CAP-25 — bounded tally returned by a controlled projector drain. Carries only counts and flags; never
   * tenant names, customer data, raw payloads, or per-event detail. {@code tenantsScanned} is the number of
   * tenants the drain visited this cycle; {@code partial} indicates the tenant scan hit its clamp (more
   * tenants may have pending work); {@code limitApplied} is the per-tenant event batch limit that was used.
   */
  public record OrderJourneyProjectionDrainSummary(
      int tenantsScanned,
      int eventsProcessed,
      int eventsSkipped,
      int eventsFailed,
      int eventsDeadLettered,
      boolean partial,
      int limitApplied,
      Instant generatedAt) {}

  /** A single bounded checkpoint failure entry for observability. */
  public record JourneyProjectionFailureDto(
      UUID eventId,
      String eventType,
      String sourceType,
      UUID sourceId,
      String status,
      String failureCode,
      String failureMessage,
      int attemptCount,
      Instant failedAt) {}

  /** Request body for an explicit, audited, idempotent projection request. */
  public record JourneyProjectionRequest(
      String sourceType,
      UUID sourceId,
      String reasonCode) {}

  /** Result of an explicit projection request — the durable event was published (idempotently). */
  public record JourneyProjectionRequestResponse(
      UUID eventId,
      String eventType,
      String sourceType,
      UUID sourceId,
      String status,
      boolean alreadyExisted,
      Instant generatedAt) {}
}
