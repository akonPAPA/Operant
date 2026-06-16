package com.orderpilot.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OP-CAP-07E DTOs for the advisory-AI deterministic validation + risk routing result.
 *
 * <p>Read-only views. They expose only bounded, typed validation/risk metadata — never raw customer
 * message text, raw document text, secrets, or a quote/order/business-mutation surface.
 */
public final class AiValidationDtos {
  private AiValidationDtos() {}

  public record AiValidationIssueView(
      String issueCode,
      String severity,
      Integer lineIndex,
      String fieldName,
      String message,
      String evidenceRef) {}

  public record AiValidationResultView(
      UUID validationId,
      UUID extractionResultId,
      UUID extractionRunId,
      UUID processingJobId,
      String sourceType,
      UUID sourceId,
      String riskLevel,
      String routingDecision,
      String status,
      int issueCount,
      String highestSeverity,
      int promptInjectionSignalCount,
      int unknownProductCount,
      boolean unknownCustomer,
      boolean advisoryOnly,
      List<AiValidationIssueView> issues,
      Instant createdAt) {}
}
