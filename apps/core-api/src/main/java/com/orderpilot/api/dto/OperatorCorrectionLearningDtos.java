package com.orderpilot.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-18 Operator Correction Learning Loop.
 *
 * Bounded, sanitized operator-correction DTOs. They expose only safe metadata: bounded
 * normalized/summary text, SHA-256 hashes (never raw previous/corrected values), confidence, status, and
 * learning eligibility. They never expose raw documents, OCR, prompts, customer messages, secrets, card
 * data, or bank credentials.
 */
public final class OperatorCorrectionLearningDtos {
  private OperatorCorrectionLearningDtos() {}

  public record OperatorCorrectionLearningRecordDto(
      UUID id,
      String correctionType,
      String sourceType,
      UUID sourceId,
      String targetType,
      UUID targetId,
      String fieldKey,
      String previousValueHash,
      String correctedValueHash,
      String normalizedCorrection,
      String correctionSummary,
      BigDecimal confidence,
      String status,
      boolean learningEligible,
      UUID linkedAiMemoryRecordId,
      Instant createdAt,
      Instant reviewedAt,
      Instant rejectedAt,
      String rejectionReason) {}

  /**
   * Records an operator correction. {@code previousValue}/{@code correctedValue} are RAW inputs that are
   * hashed (SHA-256) and discarded — they are never persisted or logged. Provide
   * {@code normalizedCorrection} only when the value is safe, domain-normalized, and non-sensitive.
   */
  public record RecordOperatorCorrectionRequest(
      String correctionType,
      String sourceType,
      UUID sourceId,
      String targetType,
      UUID targetId,
      String fieldKey,
      String previousValue,
      String correctedValue,
      String normalizedCorrection,
      String correctionSummary,
      BigDecimal confidence) {}

  public record ApproveCorrectionLearningRequest(
      String note) {}

  public record RejectCorrectionLearningRequest(
      String reason) {}

  /** Returned when an approved correction is queued for projection (event published). */
  public record CorrectionLearningProjectionResponse(
      UUID correctionId,
      String status,
      UUID publishedEventId,
      String eventStatus) {}
}
