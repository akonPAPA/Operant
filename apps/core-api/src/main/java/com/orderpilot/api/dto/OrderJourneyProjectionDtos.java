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

  /** Bounded projector health snapshot (cheap tenant-scoped counts + recent failures). */
  public record JourneyProjectionHealthDto(
      long pendingEvents,
      long failedEvents,
      long deadLetteredEvents,
      long failedCheckpoints,
      Instant lastProcessedAt,
      List<JourneyProjectionFailureDto> recentFailures,
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
