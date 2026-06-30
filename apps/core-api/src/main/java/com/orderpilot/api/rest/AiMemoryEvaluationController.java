package com.orderpilot.api.rest;

import com.orderpilot.api.dto.AiMemoryEvaluationBatchDtos.BatchCaseRequest;
import com.orderpilot.api.dto.AiMemoryEvaluationBatchDtos.BatchRunRequest;
import com.orderpilot.api.dto.AiMemoryEvaluationDtos.AddEvaluationCaseRequest;
import com.orderpilot.api.dto.AiMemoryEvaluationDtos.CreateEvaluationRunRequest;
import com.orderpilot.api.dto.AiMemoryEvaluationDtos.EvaluationCaseDto;
import com.orderpilot.api.dto.AiMemoryEvaluationDtos.EvaluationResultDto;
import com.orderpilot.api.dto.AiMemoryEvaluationDtos.EvaluationRunDto;
import com.orderpilot.application.services.trust.AiMemoryEvaluationBatchRunnerService;
import com.orderpilot.application.services.trust.AiMemoryEvaluationBatchRunnerService.BatchRunCommand;
import com.orderpilot.application.services.trust.AiMemoryEvaluationBatchRunnerService.CaseSource;
import com.orderpilot.application.services.trust.AiMemoryEvaluationBatchRunnerService.ManualCaseSpec;
import com.orderpilot.application.services.trust.AiMemoryEvaluationService;
import com.orderpilot.application.services.trust.AiMemoryEvaluationService.AddCaseCommand;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.trust.ai.AiAdvisoryTaskType;
import com.orderpilot.domain.trust.ai.AiMemoryNamespace;
import com.orderpilot.domain.trust.evaluation.AiMemoryEvaluationCase;
import com.orderpilot.domain.trust.evaluation.AiMemoryEvaluationCaseType;
import com.orderpilot.domain.trust.evaluation.AiMemoryEvaluationResult;
import com.orderpilot.domain.trust.evaluation.AiMemoryEvaluationRun;
import com.orderpilot.domain.trust.evaluation.AiMemoryEvaluationRunType;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-19 Layer C — Projected Memory Evaluation Harness surface.
 *
 * Tenant-scoped evaluation control/observability under {@code /api/v1/trust/ai-memory/evaluations}. Reads
 * require {@code TRUST_AI_MEMORY_EVALUATION_READ}; creating runs/cases require
 * {@code TRUST_AI_MEMORY_EVALUATION_WRITE}; executing a run requires the stronger
 * {@code TRUST_AI_MEMORY_EVALUATION_RUN} (see {@code ApiPermissionInterceptor}). Generic AI-memory
 * read/write never grants evaluation run. Tenant is resolved from context; ids are never trusted across
 * tenants. Evaluation never mutates memory and never creates business records.
 */
@RestController
public class AiMemoryEvaluationController {
  private final AiMemoryEvaluationService service;
  private final AiMemoryEvaluationBatchRunnerService batchRunner;

  public AiMemoryEvaluationController(
      AiMemoryEvaluationService service, AiMemoryEvaluationBatchRunnerService batchRunner) {
    this.service = service;
    this.batchRunner = batchRunner;
  }

  @PostMapping("/api/v1/trust/ai-memory/evaluations/runs")
  public EvaluationRunDto createRun(@RequestBody CreateEvaluationRunRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    AiMemoryEvaluationRun run = service.createEvaluationRun(tenantId,
        parseEnum(AiMemoryEvaluationRunType.class, request.runType(), "runType"), null);
    return toDto(run);
  }

  @PostMapping("/api/v1/trust/ai-memory/evaluations/runs/{runId}/cases")
  public EvaluationCaseDto addCase(@PathVariable UUID runId, @RequestBody AddEvaluationCaseRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    AiMemoryEvaluationCase added = service.addCase(tenantId, runId, new AddCaseCommand(
        parseEnum(AiMemoryEvaluationCaseType.class, request.caseType(), "caseType"),
        parseEnum(AiAdvisoryTaskType.class, request.taskType(), "taskType"),
        parseEnum(AiMemoryNamespace.class, request.namespace(), "namespace"),
        request.lookupKey(),
        request.expectedMemoryKey(),
        request.expectedExcludedMemoryKey(),
        request.minExpectedScore(),
        request.maxResults()));
    return toDto(added);
  }

  @PostMapping("/api/v1/trust/ai-memory/evaluations/runs/{runId}/execute")
  public EvaluationRunDto execute(@PathVariable UUID runId) {
    return toDto(service.runEvaluation(TenantContext.requireTenantId(), runId));
  }

  /**
   * OP-CAP-20 Layer B — bounded batch run: create a run, add clamped cases, and execute it in one call.
   * Because it executes, it requires the stronger {@code TRUST_AI_MEMORY_EVALUATION_RUN} (see
   * {@code ApiPermissionInterceptor}); generic AI-memory write never grants it.
   */
  @PostMapping("/api/v1/trust/ai-memory/evaluations/batch-runs")
  public EvaluationRunDto batchRun(@RequestBody BatchRunRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    List<ManualCaseSpec> specs = (request.cases() == null ? List.<BatchCaseRequest>of() : request.cases())
        .stream().map(AiMemoryEvaluationController::toManualCaseSpec).toList();
    BatchRunCommand command = new BatchRunCommand(
        parseEnum(AiMemoryEvaluationRunType.class, request.runType(), "runType"),
        parseEnum(CaseSource.class, request.caseSource(), "caseSource"),
        request.maxCases(),
        request.maxResultsPerCase(),
        Boolean.TRUE.equals(request.dryRun()),
        specs);
    return toDto(batchRunner.runBatch(tenantId, null, command));
  }

  @GetMapping("/api/v1/trust/ai-memory/evaluations/runs")
  public List<EvaluationRunDto> listRuns(
      @RequestParam(name = "runType", required = false) String runType,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "25") int size) {
    return service.listRuns(TenantContext.requireTenantId(),
        parseEnumOrNull(AiMemoryEvaluationRunType.class, runType), page, size)
        .stream().map(AiMemoryEvaluationController::toDto).toList();
  }

  @GetMapping("/api/v1/trust/ai-memory/evaluations/runs/{runId}")
  public EvaluationRunDto getRun(@PathVariable UUID runId) {
    return toDto(service.getRun(TenantContext.requireTenantId(), runId));
  }

  @GetMapping("/api/v1/trust/ai-memory/evaluations/runs/{runId}/cases")
  public List<EvaluationCaseDto> listCases(@PathVariable UUID runId) {
    return service.listCases(TenantContext.requireTenantId(), runId)
        .stream().map(AiMemoryEvaluationController::toDto).toList();
  }

  @GetMapping("/api/v1/trust/ai-memory/evaluations/runs/{runId}/results")
  public List<EvaluationResultDto> listResults(
      @PathVariable UUID runId,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "25") int size) {
    return service.listResults(TenantContext.requireTenantId(), runId, page, size)
        .stream().map(AiMemoryEvaluationController::toDto).toList();
  }

  // ----------------------------- mappers -----------------------------

  private static EvaluationRunDto toDto(AiMemoryEvaluationRun r) {
    return new EvaluationRunDto(r.getId(), r.getRunType().name(), r.getStatus().name(), r.getStartedAt(),
        r.getCompletedAt(), r.getTotalCases(), r.getPassedCases(), r.getFailedCases(), r.getAverageScore(),
        r.getCreatedAt());
  }

  private static EvaluationCaseDto toDto(AiMemoryEvaluationCase c) {
    return new EvaluationCaseDto(c.getId(), c.getRunId(), c.getCaseType().name(), c.getTaskType().name(),
        c.getNamespace().name(), c.getLookupKey(), c.getExpectedMemoryKey(), c.getExpectedExcludedMemoryKey(),
        c.getMinExpectedScore(), c.getMaxResults(), c.getStatus().name(), c.getCreatedAt());
  }

  private static EvaluationResultDto toDto(AiMemoryEvaluationResult r) {
    return new EvaluationResultDto(r.getId(), r.getRunId(), r.getCaseId(), r.getStatus().name(),
        r.getTopMemoryRecordId(), r.getTopMemoryKey(), r.getTopScore(), r.isExpectedMatched(),
        r.isExcludedUnsafe(), r.isTenantIsolated(), r.getFailureReason(), r.getCreatedAt());
  }

  private static ManualCaseSpec toManualCaseSpec(BatchCaseRequest c) {
    return new ManualCaseSpec(
        parseEnum(AiMemoryEvaluationCaseType.class, c.caseType(), "caseType"),
        parseEnum(AiAdvisoryTaskType.class, c.taskType(), "taskType"),
        parseEnum(AiMemoryNamespace.class, c.namespace(), "namespace"),
        c.lookupKey(),
        c.expectedMemoryKey(),
        c.expectedExcludedMemoryKey(),
        c.minExpectedScore());
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    try {
      return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unknown " + field + ": " + value);
    }
  }

  private static <E extends Enum<E>> E parseEnumOrNull(Class<E> type, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unknown value for " + type.getSimpleName() + ": " + value);
    }
  }
}
