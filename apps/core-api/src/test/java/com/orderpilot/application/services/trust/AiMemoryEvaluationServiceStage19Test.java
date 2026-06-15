package com.orderpilot.application.services.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.application.services.trust.AiMemoryEvaluationService.AddCaseCommand;
import com.orderpilot.application.services.trust.AiMemoryGovernanceService.CreateMemoryCommand;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.domain.trust.ai.AiAdvisoryTaskType;
import com.orderpilot.domain.trust.ai.AiMemoryActorType;
import com.orderpilot.domain.trust.ai.AiMemoryAuthorityLevel;
import com.orderpilot.domain.trust.ai.AiMemoryInvalidationReasonCode;
import com.orderpilot.domain.trust.ai.AiMemoryNamespace;
import com.orderpilot.domain.trust.ai.AiMemoryRecord;
import com.orderpilot.domain.trust.ai.AiMemorySourceType;
import com.orderpilot.domain.trust.ai.AiMemoryStatus;
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
 * OP-CAP-19 Layer C — Projected Memory Evaluation Harness. Deterministic, tenant-scoped, read-only over
 * memory; measures expected-match / unsafe-exclusion / tenant-isolation / ranking stability without
 * mutating memory or business state.
 */
@SpringBootTest
@ActiveProfiles("test")
class AiMemoryEvaluationServiceStage19Test {
  @Autowired private AiMemoryEvaluationService evaluation;
  @Autowired private AiMemoryGovernanceService memory;

  private AiMemoryRecord create(UUID tenantId, AiMemoryNamespace ns, String key,
      AiMemoryAuthorityLevel authority, BigDecimal confidence) {
    return memory.createMemoryRecord(new CreateMemoryCommand(tenantId, ns, key, AiMemoryType.HINT, authority,
        AiMemorySourceType.DOCUMENT_TRUST_RUN, UUID.randomUUID(), "ref:safe", "Hint title",
        "Safe bounded advisory summary", "norm-value", confidence, 5, null, null, null));
  }

  // ----------------------------- 21. create run/case is tenant-scoped -----------------------------

  @Test
  void createRunAndCaseAreTenantScoped() {
    UUID tenantId = UUID.randomUUID();
    AiMemoryEvaluationRun run = evaluation.createEvaluationRun(
        tenantId, AiMemoryEvaluationRunType.ADVISORY_RETRIEVAL_REGRESSION, null);
    evaluation.addCase(tenantId, run.getId(), new AddCaseCommand(AiMemoryEvaluationCaseType.EXPECT_TOP_MATCH,
        AiAdvisoryTaskType.PRODUCT_MATCH_ASSIST, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "k", "k", null, null, 5));

    assertThat(run.getTenantId()).isEqualTo(tenantId);
    assertThat(evaluation.listCases(tenantId, run.getId())).hasSize(1);
    assertThatThrownBy(() -> evaluation.getRun(UUID.randomUUID(), run.getId()))
        .isInstanceOf(NotFoundException.class);
  }

  // ----------------------------- 22. expected top match passes -----------------------------

  @Test
  void runWithExpectedTopMatchPasses() {
    UUID tenantId = UUID.randomUUID();
    create(tenantId, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "top", AiMemoryAuthorityLevel.HIGH, new BigDecimal("0.90"));
    AiMemoryEvaluationRun run = evaluation.createEvaluationRun(
        tenantId, AiMemoryEvaluationRunType.ADVISORY_RETRIEVAL_REGRESSION, null);
    evaluation.addCase(tenantId, run.getId(), new AddCaseCommand(AiMemoryEvaluationCaseType.EXPECT_TOP_MATCH,
        AiAdvisoryTaskType.PRODUCT_MATCH_ASSIST, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "top", "top", null, null, 5));

    AiMemoryEvaluationRun executed = evaluation.runEvaluation(tenantId, run.getId());

    assertThat(executed.getStatus()).isEqualTo(AiMemoryEvaluationStatus.PASSED);
    assertThat(executed.getPassedCases()).isEqualTo(1);
    assertThat(executed.getFailedCases()).isZero();
    assertThat(executed.getAverageScore()).isNotNull();
  }

  // ----------------------------- 23. excluded invalidated case passes -----------------------------

  @Test
  void excludedInvalidatedCasePasses() {
    UUID tenantId = UUID.randomUUID();
    AiMemoryRecord bad = create(tenantId, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "bad", AiMemoryAuthorityLevel.HIGH, new BigDecimal("0.90"));
    memory.invalidateMemoryRecord(tenantId, bad.getId(), AiMemoryInvalidationReasonCode.CONFLICTING_EVIDENCE,
        "newer evidence", AiMemoryActorType.OPERATOR, UUID.randomUUID());
    AiMemoryEvaluationRun run = evaluation.createEvaluationRun(tenantId, AiMemoryEvaluationRunType.UNSAFE_MEMORY_EXCLUSION, null);
    evaluation.addCase(tenantId, run.getId(), new AddCaseCommand(AiMemoryEvaluationCaseType.EXPECT_EXCLUDED_INVALIDATED,
        AiAdvisoryTaskType.PRODUCT_MATCH_ASSIST, AiMemoryNamespace.PRODUCT_ALIAS_HINT, null, null, "bad", null, 5));

    AiMemoryEvaluationRun executed = evaluation.runEvaluation(tenantId, run.getId());

    assertThat(executed.getStatus()).isEqualTo(AiMemoryEvaluationStatus.PASSED);
  }

  // ----------------------------- 24. tenant isolation case passes -----------------------------

  @Test
  void tenantIsolationCasePasses() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    create(tenantB, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "foreign", AiMemoryAuthorityLevel.HIGH, new BigDecimal("0.95"));
    AiMemoryEvaluationRun run = evaluation.createEvaluationRun(tenantA, AiMemoryEvaluationRunType.TENANT_ISOLATION, null);
    evaluation.addCase(tenantA, run.getId(), new AddCaseCommand(AiMemoryEvaluationCaseType.EXPECT_TENANT_ISOLATED,
        AiAdvisoryTaskType.PRODUCT_MATCH_ASSIST, AiMemoryNamespace.PRODUCT_ALIAS_HINT, null, null, "foreign", null, 5));

    AiMemoryEvaluationRun executed = evaluation.runEvaluation(tenantA, run.getId());

    assertThat(executed.getStatus()).isEqualTo(AiMemoryEvaluationStatus.PASSED);
  }

  // ----------------------------- 25. ranking stability is deterministic -----------------------------

  @Test
  void rankingIsStableAcrossRepeatedRuns() {
    UUID tenantId = UUID.randomUUID();
    create(tenantId, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "x", AiMemoryAuthorityLevel.HIGH, new BigDecimal("0.95"));
    create(tenantId, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "y", AiMemoryAuthorityLevel.LOW, new BigDecimal("0.60"));
    AiMemoryEvaluationRun run = evaluation.createEvaluationRun(tenantId, AiMemoryEvaluationRunType.RANKING_STABILITY, null);
    AiMemoryEvaluationCase c = evaluation.addCase(tenantId, run.getId(),
        new AddCaseCommand(AiMemoryEvaluationCaseType.EXPECT_TOP_MATCH, AiAdvisoryTaskType.PRODUCT_MATCH_ASSIST,
            AiMemoryNamespace.PRODUCT_ALIAS_HINT, "x", "x", null, null, 5));

    AiMemoryEvaluationResult first = evaluation.runCase(tenantId, c.getId());
    AiMemoryEvaluationResult second = evaluation.runCase(tenantId, c.getId());

    assertThat(first.getStatus()).isEqualTo(AiMemoryEvaluationStatus.PASSED);
    assertThat(second.getTopMemoryRecordId()).isEqualTo(first.getTopMemoryRecordId());
    assertThat(second.getTopScore()).isEqualTo(first.getTopScore());
  }

  // ----------------------------- 26. failing match records bounded reason -----------------------------

  @Test
  void failingExpectedMatchRecordsBoundedFailureReason() {
    UUID tenantId = UUID.randomUUID();
    AiMemoryEvaluationRun run = evaluation.createEvaluationRun(tenantId, AiMemoryEvaluationRunType.ADVISORY_RETRIEVAL_REGRESSION, null);
    AiMemoryEvaluationCase c = evaluation.addCase(tenantId, run.getId(),
        new AddCaseCommand(AiMemoryEvaluationCaseType.EXPECT_TOP_MATCH, AiAdvisoryTaskType.PRODUCT_MATCH_ASSIST,
            AiMemoryNamespace.PRODUCT_ALIAS_HINT, "missing", "missing", null, null, 5));

    evaluation.runEvaluation(tenantId, run.getId());
    List<AiMemoryEvaluationResult> rs = evaluation.listResults(tenantId, run.getId(), 0, 25);

    assertThat(rs).hasSize(1);
    assertThat(rs.get(0).getStatus()).isEqualTo(AiMemoryEvaluationStatus.FAILED);
    assertThat(rs.get(0).getFailureReason()).isNotNull();
    assertThat(rs.get(0).getFailureReason().length()).isLessThanOrEqualTo(280);
  }

  // ----------------------------- 27. evaluation does not mutate memory -----------------------------

  @Test
  void evaluationDoesNotMutateMemory() {
    UUID tenantId = UUID.randomUUID();
    AiMemoryRecord record = create(tenantId, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "keep", AiMemoryAuthorityLevel.HIGH, new BigDecimal("0.90"));
    AiMemoryEvaluationRun run = evaluation.createEvaluationRun(tenantId, AiMemoryEvaluationRunType.ADVISORY_RETRIEVAL_REGRESSION, null);
    evaluation.addCase(tenantId, run.getId(), new AddCaseCommand(AiMemoryEvaluationCaseType.EXPECT_TOP_MATCH,
        AiAdvisoryTaskType.PRODUCT_MATCH_ASSIST, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "keep", "keep", null, null, 5));

    evaluation.runEvaluation(tenantId, run.getId());

    AiMemoryRecord after = memory.getRecord(tenantId, record.getId());
    assertThat(after.getStatus()).isEqualTo(AiMemoryStatus.ACTIVE);
    assertThat(after.getAccessCount()).isZero();
  }

  // ----------------------------- 28. per-case maxResults is clamped -----------------------------

  @Test
  void addCaseClampsMaxResults() {
    UUID tenantId = UUID.randomUUID();
    AiMemoryEvaluationRun run = evaluation.createEvaluationRun(tenantId, AiMemoryEvaluationRunType.ADVISORY_RETRIEVAL_REGRESSION, null);
    AiMemoryEvaluationCase c = evaluation.addCase(tenantId, run.getId(),
        new AddCaseCommand(AiMemoryEvaluationCaseType.EXPECT_TOP_MATCH, AiAdvisoryTaskType.PRODUCT_MATCH_ASSIST,
            AiMemoryNamespace.PRODUCT_ALIAS_HINT, "k", "k", null, null, 1000));

    assertThat(c.getMaxResults()).isEqualTo(AiAdvisoryMemoryRetrievalService.MAX_MAX_RESULTS);
  }

  // ----------------------------- 29. result list is paged/bounded -----------------------------

  @Test
  void resultListIsPagedAndBounded() {
    UUID tenantId = UUID.randomUUID();
    create(tenantId, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "a", AiMemoryAuthorityLevel.HIGH, new BigDecimal("0.90"));
    AiMemoryEvaluationRun run = evaluation.createEvaluationRun(tenantId, AiMemoryEvaluationRunType.ADVISORY_RETRIEVAL_REGRESSION, null);
    for (int i = 0; i < 3; i++) {
      evaluation.addCase(tenantId, run.getId(), new AddCaseCommand(AiMemoryEvaluationCaseType.EXPECT_TOP_MATCH,
          AiAdvisoryTaskType.PRODUCT_MATCH_ASSIST, AiMemoryNamespace.PRODUCT_ALIAS_HINT, "a", "a", null, null, 5));
    }
    evaluation.runEvaluation(tenantId, run.getId());

    assertThat(evaluation.listResults(tenantId, run.getId(), 0, 2)).hasSize(2);
  }
}
