package com.orderpilot.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OP-CAP-20 Layer A — AI Advisory Runtime Assist DTOs.
 *
 * Bounded response shapes for the read-only runtime assist surface. The request never carries a tenant id
 * (tenant is resolved from {@code TenantContext}); responses expose only sanitized, bounded, advisory
 * metadata derived from OP-CAP-19 advisory retrieval — never raw documents/prompts/messages, normalized
 * values, or business truth. Every hint is {@code advisoryOnly = true}, and the response always restates
 * what the deterministic backend must still validate.
 */
public final class AiAdvisoryRuntimeAssistDtos {
  private AiAdvisoryRuntimeAssistDtos() {}

  /**
   * A single ranked, explainable runtime-assist hint. Carries only bounded, sanitized fields plus the
   * deterministic OP-CAP-19 score and its reason codes. {@code advisoryOnly} is always {@code true} and
   * {@code safetyLevel} states the hint may suggest but never decide.
   */
  public record RuntimeAssistHintDto(
      UUID hintId,
      UUID memoryRecordId,
      String taskType,
      List<String> reasonCodes,
      int rank,
      int score,
      BigDecimal confidence,
      String title,
      String summary,
      String evidenceSummary,
      String sourceAuthority,
      String applicability,
      String safetyLevel,
      boolean advisoryOnly,
      Instant createdAt) {}

  /**
   * Bounded runtime-assist response for one concrete workflow context. {@code deterministicValidationRequired}
   * restates the safety invariant: hints suggest, deterministic backend services decide.
   */
  public record RuntimeAssistResponse(
      String contextType,
      UUID contextId,
      String taskType,
      int requestedMaxHints,
      int returnedCount,
      boolean advisoryOnly,
      List<String> deterministicValidationRequired,
      List<RuntimeAssistHintDto> hints) {}
}
