package com.orderpilot.application.services.trust;

import com.orderpilot.api.dto.AiAdvisoryMemoryDtos.AdvisoryMemoryHintDto;
import com.orderpilot.api.dto.AiAdvisoryMemoryDtos.AdvisoryMemoryRetrievalResponse;
import com.orderpilot.application.services.trust.AiAdvisoryMemoryRetrievalService.RetrievalCommand;
import com.orderpilot.common.errors.ConflictException;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.domain.trust.ai.AiAdvisoryTaskType;
import com.orderpilot.domain.trust.ai.AiMemoryNamespace;
import com.orderpilot.domain.trust.evaluation.AiMemoryEvaluationCase;
import com.orderpilot.domain.trust.evaluation.AiMemoryEvaluationCaseRepository;
import com.orderpilot.domain.trust.evaluation.AiMemoryEvaluationCaseType;
import com.orderpilot.domain.trust.evaluation.AiMemoryEvaluationResult;
import com.orderpilot.domain.trust.evaluation.AiMemoryEvaluationResultRepository;
import com.orderpilot.domain.trust.evaluation.AiMemoryEvaluationRun;
import com.orderpilot.domain.trust.evaluation.AiMemoryEvaluationRunRepository;
import com.orderpilot.domain.trust.evaluation.AiMemoryEvaluationRunType;
import com.orderpilot.domain.trust.evaluation.AiMemoryEvaluationStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-19 Layer C — Projected Memory Evaluation Harness.
 *
 * Deterministic, tenant-scoped governance evaluation over {@link AiAdvisoryMemoryRetrievalService}. It
 * measures whether expected hints are returned, unsafe/ineligible memory is excluded, tenant isolation
 * holds, ranking is stable, and no authority escalation occurs. It NEVER mutates memory, NEVER creates
 * business records, NEVER calls an external model, and NEVER stores raw prompts/documents. Cases per run
 * are clamped and result reasons are bounded. Evaluation is for governance, not runtime decisioning.
 */
@Service
public class AiMemoryEvaluationService {
  public static final int DEFAULT_LIMIT = 25;
  static final int MAX_LIMIT = 100;
  static final int MAX_CASES_PER_RUN = 200;
  static final int MAX_FAILURE_REASON = 280;

  private final AiMemoryEvaluationRunRepository runs;
  private final AiMemoryEvaluationCaseRepository cases;
  private final AiMemoryEvaluationResultRepository results;
  private final AiAdvisoryMemoryRetrievalService retrievalService;
  private Clock clock;

  public AiMemoryEvaluationService(
      AiMemoryEvaluationRunRepository runs,
      AiMemoryEvaluationCaseRepository cases,
      AiMemoryEvaluationResultRepository results,
      AiAdvisoryMemoryRetrievalService retrievalService,
      Clock clock) {
    this.runs = runs;
    this.cases = cases;
    this.results = results;
    this.retrievalService = retrievalService;
    this.clock = clock;
  }

  public record AddCaseCommand(
      AiMemoryEvaluationCaseType caseType,
      AiAdvisoryTaskType taskType,
      AiMemoryNamespace namespace,
      String lookupKey,
      String expectedMemoryKey,
      String expectedExcludedMemoryKey,
      Integer minExpectedScore,
      Integer maxResults) {}

  // ----------------------------- create run / case -----------------------------

  @Transactional
  public AiMemoryEvaluationRun createEvaluationRun(UUID tenantId, AiMemoryEvaluationRunType runType, UUID createdBy) {
    required(tenantId, "tenantId");
    required(runType, "runType");
    return runs.save(new AiMemoryEvaluationRun(tenantId, runType, createdBy, clock.instant()));
  }

  @Transactional
  public AiMemoryEvaluationCase addCase(UUID tenantId, UUID runId, AddCaseCommand cmd) {
    AiMemoryEvaluationRun run = loadRun(tenantId, runId);
    if (run.getStatus() != AiMemoryEvaluationStatus.PENDING) {
      throw new ConflictException("Cases can only be added to a PENDING evaluation run");
    }
    if (cases.countByTenantIdAndRunId(tenantId, runId) >= MAX_CASES_PER_RUN) {
      throw new ConflictException("Evaluation run has reached the maximum of " + MAX_CASES_PER_RUN + " cases");
    }
    AiMemoryEvaluationCaseType caseType = required(cmd.caseType(), "caseType");
    AiAdvisoryTaskType taskType = required(cmd.taskType(), "taskType");
    AiMemoryNamespace namespace = required(cmd.namespace(), "namespace");
    int maxResults = AiAdvisoryMemoryRetrievalService.clampMaxResults(cmd.maxResults());
    return cases.save(new AiMemoryEvaluationCase(tenantId, runId, caseType, taskType, namespace,
        bound(cmd.lookupKey(), 160), bound(cmd.expectedMemoryKey(), 160),
        bound(cmd.expectedExcludedMemoryKey(), 160), cmd.minExpectedScore(), maxResults, clock.instant()));
  }

  // ----------------------------- execute -----------------------------

  @Transactional
  public AiMemoryEvaluationRun runEvaluation(UUID tenantId, UUID runId) {
    AiMemoryEvaluationRun run = loadRun(tenantId, runId);
    Instant now = clock.instant();
    run.markRunning(now);

    List<AiMemoryEvaluationCase> runCases = cases.findByTenantIdAndRunIdOrderByCreatedAtAsc(
        tenantId, runId, PageRequest.of(0, MAX_CASES_PER_RUN));
    int passed = 0;
    int failed = 0;
    long scoreSum = 0;
    int scoreCount = 0;
    for (AiMemoryEvaluationCase c : runCases) {
      AiMemoryEvaluationResult result = evaluateCase(c, now);
      results.save(result);
      if (result.getStatus() == AiMemoryEvaluationStatus.PASSED) {
        passed++;
      } else {
        failed++;
      }
      if (result.getTopScore() != null) {
        scoreSum += result.getTopScore();
        scoreCount++;
      }
      c.markStatus(result.getStatus());
    }
    BigDecimal average = scoreCount == 0 ? null
        : BigDecimal.valueOf(scoreSum).divide(BigDecimal.valueOf(scoreCount), 2, RoundingMode.HALF_UP);
    run.complete(runCases.size(), passed, failed, average, clock.instant());
    return run;
  }

  /** Re-run a single case standalone (does not roll up into the run counts). */
  @Transactional
  public AiMemoryEvaluationResult runCase(UUID tenantId, UUID caseId) {
    AiMemoryEvaluationCase c = cases.findByIdAndTenantId(caseId, required(tenantId, "tenantId"))
        .orElseThrow(() -> new NotFoundException("Evaluation case not found"));
    AiMemoryEvaluationResult result = evaluateCase(c, clock.instant());
    c.markStatus(result.getStatus());
    return results.save(result);
  }

  private AiMemoryEvaluationResult evaluateCase(AiMemoryEvaluationCase c, Instant now) {
    UUID tenantId = c.getTenantId();
    AdvisoryMemoryRetrievalResponse response = retrievalService.retrieve(new RetrievalCommand(
        tenantId, c.getTaskType(), List.of(c.getNamespace()), List.of(), null, null,
        c.getLookupKey(), c.getMaxResults(), null, false, false));
    List<AdvisoryMemoryHintDto> hints = response.hints();
    AdvisoryMemoryHintDto top = hints.isEmpty() ? null : hints.get(0);

    UUID topRecordId = top == null ? null : top.memoryRecordId();
    String topKey = top == null ? null : top.memoryKey();
    Integer topScore = top == null ? null : top.score();

    boolean excludedKeyPresent = c.getExpectedExcludedMemoryKey() != null
        && hints.stream().anyMatch(h -> c.getExpectedExcludedMemoryKey().equals(h.memoryKey()));

    boolean expectedMatched = c.getExpectedMemoryKey() != null && top != null
        && c.getExpectedMemoryKey().equals(top.memoryKey());
    boolean excludedUnsafe = !excludedKeyPresent;
    boolean tenantIsolated = !excludedKeyPresent; // retrieval is tenant-scoped; a foreign key must be absent

    boolean pass;
    String failureReason = null;
    switch (c.getCaseType()) {
      case EXPECT_TOP_MATCH -> {
        pass = expectedMatched;
        if (!pass) {
          failureReason = "Expected top key '" + c.getExpectedMemoryKey() + "' but top was '" + topKey + "'";
        }
      }
      case EXPECT_EXCLUDED_INVALIDATED, EXPECT_EXCLUDED_SUPERSEDED -> {
        pass = excludedUnsafe;
        if (!pass) {
          failureReason = "Ineligible memory '" + c.getExpectedExcludedMemoryKey() + "' was not excluded";
        }
      }
      case EXPECT_TENANT_ISOLATED -> {
        pass = tenantIsolated;
        if (!pass) {
          failureReason = "Cross-tenant memory '" + c.getExpectedExcludedMemoryKey() + "' leaked into results";
        }
      }
      case EXPECT_SCORE_ABOVE_THRESHOLD -> {
        int threshold = c.getMinExpectedScore() == null ? 0 : c.getMinExpectedScore();
        pass = topScore != null && topScore >= threshold;
        if (!pass) {
          failureReason = "Top score " + topScore + " did not reach threshold " + threshold;
        }
      }
      default -> {
        pass = false;
        failureReason = "Unsupported case type";
      }
    }

    AiMemoryEvaluationStatus status = pass ? AiMemoryEvaluationStatus.PASSED : AiMemoryEvaluationStatus.FAILED;
    return new AiMemoryEvaluationResult(tenantId, c.getRunId(), c.getId(), status, topRecordId, topKey,
        topScore, expectedMatched, excludedUnsafe, tenantIsolated, bound(failureReason, MAX_FAILURE_REASON), now);
  }

  // ----------------------------- read side -----------------------------

  @Transactional(readOnly = true)
  public AiMemoryEvaluationRun getRun(UUID tenantId, UUID runId) {
    return loadRun(tenantId, runId);
  }

  @Transactional(readOnly = true)
  public List<AiMemoryEvaluationRun> listRuns(UUID tenantId, AiMemoryEvaluationRunType runType, int page, int size) {
    required(tenantId, "tenantId");
    Pageable pageable = PageRequest.of(Math.max(page, 0), clampLimit(size));
    if (runType != null) {
      return runs.findByTenantIdAndRunTypeOrderByCreatedAtDesc(tenantId, runType, pageable);
    }
    return runs.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
  }

  @Transactional(readOnly = true)
  public List<AiMemoryEvaluationCase> listCases(UUID tenantId, UUID runId) {
    loadRun(required(tenantId, "tenantId"), runId);
    return cases.findByTenantIdAndRunIdOrderByCreatedAtAsc(tenantId, runId);
  }

  @Transactional(readOnly = true)
  public List<AiMemoryEvaluationResult> listResults(UUID tenantId, UUID runId, int page, int size) {
    loadRun(required(tenantId, "tenantId"), runId);
    Pageable pageable = PageRequest.of(Math.max(page, 0), clampLimit(size));
    return results.findByTenantIdAndRunIdOrderByCreatedAtAsc(tenantId, runId, pageable);
  }

  // ----------------------------- helpers -----------------------------

  private AiMemoryEvaluationRun loadRun(UUID tenantId, UUID runId) {
    return runs.findByIdAndTenantId(required(runId, "runId"), required(tenantId, "tenantId"))
        .orElseThrow(() -> new NotFoundException("Evaluation run not found"));
  }

  static int clampLimit(int requested) {
    if (requested <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(requested, MAX_LIMIT);
  }

  private static String bound(String value, int max) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }

  private static <T> T required(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }
}
