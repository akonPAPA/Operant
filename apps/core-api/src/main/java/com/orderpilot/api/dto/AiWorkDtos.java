package com.orderpilot.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-07A AI Agent Work Layer DTOs.
 *
 * <p>Responses expose advisory suggestion fields only. They never expose secrets, tokens, raw
 * provider prompts, or trusted business state. {@code structuredPayloadJson} and
 * {@code evidenceRefsJson} are JSON strings the operator UI parses for display.
 */
public final class AiWorkDtos {
  private AiWorkDtos() {}

  /** Create an advisory AI work suggestion for a source object. */
  public record CreateAiWorkSuggestionRequest(
      String workType,
      String sourceType,
      UUID sourceId,
      String contextText,
      String idempotencyKey) {}

  /** Create an advisory suggestion from a backend-resolved resource context. */
  public record CreateContextualAiWorkSuggestionRequest(
      String workType,
      String idempotencyKey) {}

  /** Operator accept/reject decision. Advisory only — never approves business state. */
  public record AiWorkDecisionRequest(String reason) {}

  public record AiWorkSuggestionResponse(
      UUID id,
      String workType,
      String sourceType,
      String status,
      String strategyVersion,
      String riskLevel,
      BigDecimal confidence,
      String generatedText,
      String structuredPayloadJson,
      String evidenceRefsJson,
      boolean advisoryOnly,
      Instant createdAt,
      Instant updatedAt,
      Instant decidedAt,
      String decisionReason) {}
}
