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
}
