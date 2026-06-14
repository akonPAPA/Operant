package com.orderpilot.application.services.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.application.services.trust.AiMemoryEvaluationBatchRunnerService.BatchRunCommand;
import com.orderpilot.application.services.trust.AiMemoryEvaluationBatchRunnerService.CaseSource;
import com.orderpilot.application.services.trust.AiMemoryEvaluationBatchRunnerService.ManualCaseSpec;
import com.orderpilot.application.services.trust.AiMemoryGovernanceService.CreateMemoryCommand;
import com.orderpilot.common.errors.ConflictException;
import com.orderpilot.domain.trust.ai.AiAdvisoryTaskType;
import com.orderpilot.domain.trust.ai.AiMemoryAuthorityLevel;
import com.orderpilot.domain.trust.ai.AiMemoryNamespace;
import com.orderpilot.domain.trust.ai.AiMemoryRecord;
import com.orderpilot.domain.trust.ai.AiMemorySourceType;
import com.orderpilot.domain.trust.ai.AiMemoryType;
import com.orderpilot.domain.trust.evaluation.AiMemoryEvaluationCase;
import com.orderpilot.domain.trust.evaluation.AiMemoryEvaluationCaseType;
import com.orderpilot.domain.trust.evaluation.AiMemoryEvaluationResult;
import com.orderpilot.domain.trust.evaluation.AiMemoryEvaluationRun;
import com.orderpilot.domain.trust.evaluation.AiMemoryEvaluationRunType;
import com.orderpilot.domain.trust.evaluation.AiMemoryEvaluationStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-20 Layer B — Bounded Evaluation Batch Runner Foundation. Bounded, tenant-scoped, deterministic;
 * reuses OP-CAP-19 evaluation entities; clamps sizes; refuses unbounded sources and duplicate active runs.
 */
@SpringBootTest
@ActiveProfiles("test")
class AiMemoryEvaluationBatchRunnerServiceStage20Test {
  @Autowired private AiMemoryEvaluationBatchRunnerService batchRunner;
  @Autowired private AiMemoryEvaluationService evaluation;
  @Autowired private AiMemoryGovernanceService memory;

  private AiMemoryRecord create(UUID tenantId, AiMemoryNamespace ns, String key,
      AiMemoryAuthorityLevel authority, BigDecimal confidence) {
    return memory.createMemoryRecord(new CreateMemoryCommand(tenantId, ns, key, AiMemoryType.HINT, authority,
        AiMemorySourceType.DOCUMENT_TRUST_RUN, UUID.randomUUID(), "ref:safe", "Hint title",
        "Safe bounded advisory summary", "norm-value", confidence, 5, null, null, null));
  }

  private ManualCaseSpec topMatch(String key) {
    return new ManualCaseSpec(AiMemoryEvaluationCaseType.EXPECT_TOP_MATCH, AiAdvisoryTaskType.PRODUCT_MATCH_ASSIST,
        AiMemoryNamespace.PRODUCT_ALIAS_HINT, key, key, null, null);
  }

  // ----------------------------- manual cases produce run/case/result records -----------------------------

  @Test
  void manualCasesProduceRunCaseAndResultRecords() {
    UUID tenantId = UUID.randomUUID();
    create(tenantId, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "top", AiMemoryAuthorityLevel.HIGH, new BigDecimal("0.90"));

    AiMemoryEvaluationRun run = batchRunner.runBatch(tenantId, null, new BatchRunCommand(
        AiMemoryEvaluationRunType.ADVISORY_RETRIEVAL_REGRESSION, CaseSource.MANUAL_CASES, null, null, false,
        List.of(topMatch("top"))));

    assertThat(run.getStatus()).isEqualTo(AiMemoryEvaluationStatus.PASSED);
    assertThat(evaluation.listCases(tenantId, run.getId())).hasSize(1);
    assertThat(evaluation.listResults(tenantId, run.getId(), 0, 25)).hasSize(1);
  }

  // ----------------------------- maxCases is enforced -----------------------------

  @Test
  void maxCasesIsEnforced() {
    UUID tenantId = UUID.randomUUID();
    AiMemoryEvaluationRun run = batchRunner.runBatch(tenantId, null, new BatchRunCommand(
        AiMemoryEvaluationRunType.ADVISORY_RETRIEVAL_REGRESSION, CaseSource.MANUAL_CASES, 2, null, true,
        List.of(topMatch("a"), topMatch("b"), topMatch("c"))));

    assertThat(evaluation.listCases(tenantId, run.getId())).hasSize(2);
  }

  // ----------------------------- maxResultsPerCase is clamped -----------------------------

  @Test
  void maxResultsPerCaseIsClamped() {
    UUID tenantId = UUID.randomUUID();
    AiMemoryEvaluationRun run = batchRunner.runBatch(tenantId, null, new BatchRunCommand(
        AiMemoryEvaluationRunType.ADVISORY_RETRIEVAL_REGRESSION, CaseSource.MANUAL_CASES, null, 1000, true,
        List.of(topMatch("a"))));

    List<AiMemoryEvaluationCase> cases = evaluation.listCases(tenantId, run.getId());
    assertThat(cases).hasSize(1);
    assertThat(cases.get(0).getMaxResults()).isEqualTo(AiMemoryEvaluationBatchRunnerService.MAX_RESULTS_PER_CASE);
  }

  // ----------------------------- failure reason is bounded -----------------------------

  @Test
  void failureReasonIsBounded() {
    UUID tenantId = UUID.randomUUID();
    AiMemoryEvaluationRun run = batchRunner.runBatch(tenantId, null, new BatchRunCommand(
        AiMemoryEvaluationRunType.ADVISORY_RETRIEVAL_REGRESSION, CaseSource.MANUAL_CASES, null, null, false,
        List.of(topMatch("missing"))));

    List<AiMemoryEvaluationResult> results = evaluation.listResults(tenantId, run.getId(), 0, 25);
    assertThat(results).hasSize(1);
    assertThat(results.get(0).getStatus()).isEqualTo(AiMemoryEvaluationStatus.FAILED);
    assertThat(results.get(0).getFailureReason()).isNotNull();
    assertThat(results.get(0).getFailureReason().length()).isLessThanOrEqualTo(280);
  }

  // ----------------------------- tenant isolation -----------------------------

  @Test
  void tenantIsolationCasePasses() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    create(tenantB, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "foreign", AiMemoryAuthorityLevel.HIGH, new BigDecimal("0.95"));

    AiMemoryEvaluationRun run = batchRunner.runBatch(tenantA, null, new BatchRunCommand(
        AiMemoryEvaluationRunType.TENANT_ISOLATION, CaseSource.MANUAL_CASES, null, null, false,
        List.of(new ManualCaseSpec(AiMemoryEvaluationCaseType.EXPECT_TENANT_ISOLATED,
            AiAdvisoryTaskType.PRODUCT_MATCH_ASSIST, AiMemoryNamespace.PRODUCT_ALIAS_HINT, null, null, "foreign", null))));

    assertThat(run.getStatus()).isEqualTo(AiMemoryEvaluationStatus.PASSED);
  }

  // ----------------------------- unbounded case sources are refused -----------------------------

  @Test
  void unsupportedCaseSourceIsRejected() {
    UUID tenantId = UUID.randomUUID();
    assertThatThrownBy(() -> batchRunner.runBatch(tenantId, null, new BatchRunCommand(
        AiMemoryEvaluationRunType.OPERATOR_CORRECTION_PROJECTION, CaseSource.RECENT_CORRECTIONS, null, null, false,
        List.of())))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void emptyManualCasesIsRejected() {
    UUID tenantId = UUID.randomUUID();
    assertThatThrownBy(() -> batchRunner.runBatch(tenantId, null, new BatchRunCommand(
        AiMemoryEvaluationRunType.ADVISORY_RETRIEVAL_REGRESSION, CaseSource.MANUAL_CASES, null, null, false,
        List.of())))
        .isInstanceOf(ConflictException.class);
  }

  // ----------------------------- duplicate active run is refused -----------------------------

  @Test
  void duplicateActiveRunIsRejected() {
    UUID tenantId = UUID.randomUUID();
    // A pre-existing PENDING run of the same type blocks a new batch run.
    evaluation.createEvaluationRun(tenantId, AiMemoryEvaluationRunType.RANKING_STABILITY, null);

    assertThatThrownBy(() -> batchRunner.runBatch(tenantId, null, new BatchRunCommand(
        AiMemoryEvaluationRunType.RANKING_STABILITY, CaseSource.MANUAL_CASES, null, null, false,
        List.of(topMatch("a")))))
        .isInstanceOf(ConflictException.class);
  }

  // ----------------------------- dry run creates but does not execute -----------------------------

  @Test
  void dryRunCreatesCasesButDoesNotExecute() {
    UUID tenantId = UUID.randomUUID();
    AiMemoryEvaluationRun run = batchRunner.runBatch(tenantId, null, new BatchRunCommand(
        AiMemoryEvaluationRunType.ADVISORY_RETRIEVAL_REGRESSION, CaseSource.MANUAL_CASES, null, null, true,
        List.of(topMatch("a"))));

    assertThat(run.getStatus()).isEqualTo(AiMemoryEvaluationStatus.PENDING);
    assertThat(evaluation.listResults(tenantId, run.getId(), 0, 25)).isEmpty();
  }
}
