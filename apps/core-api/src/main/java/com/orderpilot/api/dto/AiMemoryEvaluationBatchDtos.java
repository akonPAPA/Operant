package com.orderpilot.api.dto;

import java.util.List;

/**
 * OP-CAP-20 Layer B — Bounded Evaluation Batch Runner DTOs.
 *
 * Bounded request shapes for starting a single bounded evaluation run that reuses the OP-CAP-19 evaluation
 * infrastructure (run/case/result entities). The request never carries a tenant id (resolved from
 * {@code TenantContext}); all sizes are clamped server-side. Only {@code MANUAL_CASES} is fully supported;
 * other case sources are rejected rather than allowed to perform an unbounded scan.
 */
public final class AiMemoryEvaluationBatchDtos {
  private AiMemoryEvaluationBatchDtos() {}

  /**
   * Batch run request. {@code maxCases} (≤ 200) and {@code maxResultsPerCase} (≤ 20) are clamped. When
   * {@code dryRun} is true the run and cases are created but not executed.
   */
  public record BatchRunRequest(
      String runType,
      String caseSource,
      Integer maxCases,
      Integer maxResultsPerCase,
      Boolean dryRun,
      List<BatchCaseRequest> cases) {}

  /** A single bounded manual evaluation case. Mirrors the OP-CAP-19 add-case shape, minus per-case maxResults. */
  public record BatchCaseRequest(
      String caseType,
      String taskType,
      String namespace,
      String lookupKey,
      String expectedMemoryKey,
      String expectedExcludedMemoryKey,
      Integer minExpectedScore) {}
}
