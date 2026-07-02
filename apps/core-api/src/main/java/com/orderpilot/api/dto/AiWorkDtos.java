package com.orderpilot.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OP-CAP-07A AI Agent Work Layer DTOs.
 *
 * <p>Responses expose typed, allowlisted advisory fields only. They never expose secrets, tokens, raw
 * provider prompts, raw JSON payloads, or trusted business state. Idempotency is carried on the
 * {@code Idempotency-Key} request header, not in the body.
 */
public final class AiWorkDtos {
  private AiWorkDtos() {}

  /** Create an advisory AI work suggestion for a source object. */
  public record CreateAiWorkSuggestionRequest(
      String workType,
      String sourceType,
      UUID sourceId,
      String contextText) {}

  /** Create an advisory suggestion from a backend-resolved resource context. */
  public record CreateContextualAiWorkSuggestionRequest(String workType) {}

  /** Operator accept/reject decision. Advisory only — never approves business state. */
  public record AiWorkDecisionRequest(String reason) {}

  public record AiWorkDisplayField(
      String label, String value, BigDecimal confidence, String sourceLabel) {}

  public record AiWorkEvidenceItem(String label, String excerpt, Integer page, String field) {}

  public record AiWorkNextActionCandidate(
      String actionCode, String label, boolean requiresHumanApproval) {}

  public record AiWorkSuggestionResponse(
      UUID id,
      String workType,
      String sourceType,
      String status,
      String strategyVersion,
      String riskLevel,
      BigDecimal confidence,
      String summary,
      List<AiWorkDisplayField> displayFields,
      List<AiWorkEvidenceItem> evidence,
      List<AiWorkNextActionCandidate> nextActionCandidates,
      List<String> riskFlags,
      boolean advisoryOnly,
      Instant createdAt,
      Instant updatedAt,
      Instant decidedAt,
      String decisionReason) {}
}
