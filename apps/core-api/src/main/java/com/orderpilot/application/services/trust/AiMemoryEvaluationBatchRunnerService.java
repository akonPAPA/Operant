package com.orderpilot.application.services.trust;

import com.orderpilot.application.services.trust.AiMemoryEvaluationService.AddCaseCommand;
import com.orderpilot.common.errors.ConflictException;
import com.orderpilot.domain.trust.ai.AiAdvisoryTaskType;
import com.orderpilot.domain.trust.ai.AiMemoryNamespace;
import com.orderpilot.domain.trust.evaluation.AiMemoryEvaluationCaseType;
import com.orderpilot.domain.trust.evaluation.AiMemoryEvaluationRun;
import com.orderpilot.domain.trust.evaluation.AiMemoryEvaluationRunRepository;
import com.orderpilot.domain.trust.evaluation.AiMemoryEvaluationRunType;
import com.orderpilot.domain.trust.evaluation.AiMemoryEvaluationStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-20 Layer B — Bounded Evaluation Batch Runner Foundation.
 *
 * Starts a single bounded, deterministic, tenant-scoped evaluation run by reusing the OP-CAP-19
 * {@link AiMemoryEvaluationService} (run/case/result entities). It clamps case counts and per-case max
 * results, refuses unbounded case sources, and guards against a duplicate active run for the same
 * tenant/run-type. There is no always-on scheduler in this repository, so this is a manual-trigger
 * foundation only — it never starts heavy work automatically.
 *
 * <p>Safety: evaluation is read-only with respect to memory and never touches business state. The runner
 * adds no new authority — it only orchestrates existing bounded OP-CAP-19 operations.
 */
@Service
public class AiMemoryEvaluationBatchRunnerService {
  static final int MAX_CASES = AiMemoryEvaluationService.MAX_CASES_PER_RUN; // 200
  static final int DEFAULT_MAX_CASES = 50;
  static final int MAX_RESULTS_PER_CASE = 20;
  static final int DEFAULT_RESULTS_PER_CASE = 10;

  /** Active statuses that block a duplicate batch run for the same tenant/run-type. */
  private static final List<AiMemoryEvaluationStatus> ACTIVE_STATUSES =
      List.of(AiMemoryEvaluationStatus.PENDING, AiMemoryEvaluationStatus.RUNNING);

  private final AiMemoryEvaluationService evaluationService;
  private final AiMemoryEvaluationRunRepository runs;

  public AiMemoryEvaluationBatchRunnerService(
      AiMemoryEvaluationService evaluationService, AiMemoryEvaluationRunRepository runs) {
    this.evaluationService = evaluationService;
    this.runs = runs;
  }

  /** Where the run's cases come from. Only {@link #MANUAL_CASES} is fully supported in OP-CAP-20. */
  public enum CaseSource {
    MANUAL_CASES,
    RECENT_CORRECTIONS,
    RECENT_TRUST_EVENTS
  }

  public record ManualCaseSpec(
      AiMemoryEvaluationCaseType caseType,
      AiAdvisoryTaskType taskType,
      AiMemoryNamespace namespace,
      String lookupKey,
      String expectedMemoryKey,
      String expectedExcludedMemoryKey,
      Integer minExpectedScore) {}

  public record BatchRunCommand(
      AiMemoryEvaluationRunType runType,
      CaseSource caseSource,
      Integer maxCases,
      Integer maxResultsPerCase,
      boolean dryRun,
      List<ManualCaseSpec> manualCases) {}

  /**
   * Creates a bounded evaluation run, adds clamped cases, and (unless {@code dryRun}) executes it via the
   * OP-CAP-19 evaluation service. Returns the resulting run summary.
   */
  @Transactional
  public AiMemoryEvaluationRun runBatch(UUID tenantId, UUID createdBy, BatchRunCommand cmd) {
    required(tenantId, "tenantId");
    AiMemoryEvaluationRunType runType = required(required(cmd, "command").runType(), "runType");
    CaseSource caseSource = required(cmd.caseSource(), "caseSource");
    if (caseSource != CaseSource.MANUAL_CASES) {
      // RECENT_CORRECTIONS / RECENT_TRUST_EVENTS are scaffolded only: there is no obviously safe bounded
      // query source for them yet, and OP-CAP-20 must never introduce an unbounded tenant-wide scan.
      throw new ConflictException("Case source " + caseSource + " is not yet supported; use MANUAL_CASES");
    }

    int maxCases = clamp(cmd.maxCases(), DEFAULT_MAX_CASES, MAX_CASES);
    int maxResultsPerCase = clamp(cmd.maxResultsPerCase(), DEFAULT_RESULTS_PER_CASE, MAX_RESULTS_PER_CASE);

    List<ManualCaseSpec> specs = cmd.manualCases() == null ? List.of() : cmd.manualCases();
    if (specs.isEmpty()) {
      throw new ConflictException("MANUAL_CASES batch run requires at least one case");
    }

    // Concurrency/idempotency guard: refuse a duplicate active run for the same tenant + run type.
    if (runs.existsByTenantIdAndRunTypeAndStatusIn(tenantId, runType, ACTIVE_STATUSES)) {
      throw new ConflictException("An active evaluation run already exists for run type " + runType);
    }

    AiMemoryEvaluationRun run = evaluationService.createEvaluationRun(tenantId, runType, createdBy);

    int added = 0;
    for (ManualCaseSpec spec : specs) {
      if (added >= maxCases) {
        break; // hard clamp: never exceed the bounded case budget
      }
      evaluationService.addCase(tenantId, run.getId(), new AddCaseCommand(
          required(spec.caseType(), "caseType"),
          required(spec.taskType(), "taskType"),
          required(spec.namespace(), "namespace"),
          spec.lookupKey(),
          spec.expectedMemoryKey(),
          spec.expectedExcludedMemoryKey(),
          spec.minExpectedScore(),
          maxResultsPerCase));
      added++;
    }

    if (cmd.dryRun()) {
      return run; // created PENDING with cases; not executed
    }
    return evaluationService.runEvaluation(tenantId, run.getId());
  }

  // ----------------------------- helpers -----------------------------

  private static int clamp(Integer requested, int defaultValue, int max) {
    if (requested == null || requested <= 0) {
      return defaultValue;
    }
    return Math.min(requested, max);
  }

  private static <T> T required(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }
}
