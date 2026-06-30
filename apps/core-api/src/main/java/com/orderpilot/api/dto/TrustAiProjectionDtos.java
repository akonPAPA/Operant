package com.orderpilot.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-18 Trust/AI Event Projector Runtime.
 *
 * Bounded, read-only projection-runtime DTOs. They expose only safe event/checkpoint governance metadata
 * (type, status, bounded summary, source pointers, retry/failure counters) — never raw documents, OCR,
 * prompts, customer messages, secrets, or credentials.
 */
public final class TrustAiProjectionDtos {
  private TrustAiProjectionDtos() {}

  public record TrustAiDomainEventDto(
      UUID id,
      String eventType,
      String sourceType,
      UUID sourceId,
      String subjectType,
      UUID subjectId,
      String status,
      int payloadVersion,
      String payloadSummary,
      Instant occurredAt,
      Instant createdAt,
      Instant processedAt,
      Instant failedAt,
      String failureCode,
      String failureMessage,
      int retryCount,
      Instant nextRetryAt) {}

  public record TrustAiProjectionCheckpointDto(
      UUID id,
      String projectorName,
      UUID eventId,
      String eventType,
      String sourceType,
      UUID sourceId,
      String status,
      String projectedRecordType,
      UUID projectedRecordId,
      int attemptCount,
      Instant startedAt,
      Instant completedAt,
      Instant failedAt,
      String failureCode,
      String failureMessage,
      Instant updatedAt) {}

  /** Manual publish request (internal/admin/testing). idempotencyKey is required and tenant-unique. */
  public record PublishTrustAiEventRequest(
      String eventType,
      String sourceType,
      UUID sourceId,
      String subjectType,
      UUID subjectId,
      String idempotencyKey,
      String payloadSummary,
      Instant occurredAt) {}

  public record ProcessTrustAiEventsResponse(
      int requested,
      int processed,
      int skipped,
      int failed,
      int deadLettered,
      Instant ranAt) {}

  /** Bounded view of a dead-lettered (or failed) event for the observability read API. */
  public record ProjectionFailureDto(
      UUID eventId,
      String eventType,
      String sourceType,
      UUID sourceId,
      String status,
      String failureCode,
      String failureMessage,
      int retryCount,
      Instant failedAt) {}
}
