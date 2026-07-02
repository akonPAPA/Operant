package com.orderpilot.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-19 Layer C — Projected Memory Evaluation Harness DTOs.
 *
 * Bounded request/response shapes for the evaluation surface. Requests never carry a tenant id (resolved
 * from {@code TenantContext}); responses expose only deterministic counts, scores, memory keys, and a
 * bounded failure reason — never raw content.
 */
public final class AiMemoryEvaluationDtos {
  private AiMemoryEvaluationDtos() {}

  public record CreateEvaluationRunRequest(String runType) {}

  public record AddEvaluationCaseRequest(
      String caseType,
      String taskType,
      String namespace,
      String lookupKey,
      String expectedMemoryKey,
      String expectedExcludedMemoryKey,
      Integer minExpectedScore,
      Integer maxResults) {}

  public record EvaluationRunDto(
      UUID id,
      String runType,
      String status,
      Instant startedAt,
      Instant completedAt,
      int totalCases,
      int passedCases,
      int failedCases,
      BigDecimal averageScore,
      Instant createdAt) {}

  public record EvaluationCaseDto(
      UUID id,
      UUID runId,
      String caseType,
      String taskType,
      String namespace,
      String lookupKey,
      String expectedMemoryKey,
      String expectedExcludedMemoryKey,
      Integer minExpectedScore,
      int maxResults,
      String status,
      Instant createdAt) {}

  public record EvaluationResultDto(
      UUID id,
      UUID runId,
      UUID caseId,
      String status,
      UUID topMemoryRecordId,
      String topMemoryKey,
      Integer topScore,
      boolean expectedMatched,
      boolean excludedUnsafe,
      boolean tenantIsolated,
      Instant createdAt) {}
}
