package com.orderpilot.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-07F DTOs for the AI validation handoff layer.
 *
 * <p>Stable, frontend-friendly, read-only view. Exposes only bounded, typed handoff metadata — never
 * raw customer/document text, the full extraction result JSON, secrets, or a quote/order surface.
 */
public final class AiValidationHandoffDtos {
  private AiValidationHandoffDtos() {}

  public record AiValidationHandoffView(
      UUID handoffId,
      UUID validationId,
      UUID extractionResultId,
      UUID extractionRunId,
      UUID processingJobId,
      String sourceType,
      UUID sourceId,
      String status,
      String routingDecision,
      String riskLevel,
      String intent,
      String customerRef,
      int lineCount,
      int issueCount,
      String highestSeverity,
      int promptInjectionSignalCount,
      boolean unknownCustomer,
      boolean draftEligible,
      String issueSummary,
      Instant createdAt,
      Instant updatedAt) {}

  public record AiHandoffStartReviewRequest(String reviewedBy) {}

  public record AiHandoffDecisionRequest(
      String decision,
      String reasonCode,
      String note,
      String reviewedBy) {}

  public record AiHandoffCorrectionRequest(
      String correctionSummary,
      String correctedIntent,
      String correctedCustomerRef,
      Integer correctedLineCount,
      String reviewedBy) {}

  public record AiHandoffReviewView(
      UUID reviewId,
      UUID handoffId,
      UUID validationId,
      String handoffStatus,
      String routingDecision,
      String riskLevel,
      boolean draftEligible,
      String reviewStatus,
      String decision,
      String reasonCode,
      String note,
      String correctionSummary,
      String correctedIntent,
      String correctedCustomerRef,
      Integer correctedLineCount,
      String reviewedBy,
      String externalExecution,
      Instant createdAt,
      Instant updatedAt) {}

  /**
   * OP-CAP-08B review-queue item. Bounded operator-queue projection of a handoff plus its effective
   * review status. Never carries raw result JSON, document text, or customer message body.
   */
  public record AiHandoffReviewQueueItem(
      UUID handoffId,
      UUID validationId,
      UUID extractionResultId,
      UUID processingJobId,
      String routingDecision,
      String riskLevel,
      String handoffStatus,
      String reviewStatus,
      boolean draftEligible,
      String intent,
      String customerRef,
      int lineCount,
      int issueCount,
      String highestSeverity,
      Instant updatedAt) {}

  /**
   * OP-CAP-08B draft-preparation candidate contract. This is a CONTRACT/DTO only — it never creates a
   * quote/order/draft and never triggers an external write. {@code draftPreparationAllowed} is always
   * true here because the endpoint only succeeds once review status is {@code DRAFT_PREPARATION_READY}.
   */
  public record AiHandoffDraftPreparationCandidate(
      UUID handoffId,
      UUID validationId,
      UUID extractionResultId,
      UUID processingJobId,
      String intent,
      String correctedIntent,
      String customerRef,
      String correctedCustomerRef,
      int lineCount,
      Integer correctedLineCount,
      String routingDecision,
      String riskLevel,
      String reviewDecision,
      String issueSummary,
      String correctionSummary,
      String externalExecution,
      boolean draftPreparationAllowed) {}
}
