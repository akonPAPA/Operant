package com.orderpilot.integration.testdb;

import static com.orderpilot.support.TestTenantFixtures.TENANT_A;
import static com.orderpilot.support.TestTenantFixtures.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.AiResultIntakeDtos.AiProcessingResultIntakeRequest;
import com.orderpilot.api.dto.AiResultIntakeDtos.AiProcessingResultIntakeResponse;
import com.orderpilot.application.services.WorkerJobLeaseService;
import com.orderpilot.application.services.extraction.AiWorkerResultIntakeService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEvent;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.extraction.ExtractionRunRepository;
import com.orderpilot.domain.extraction.ExtractionResultRepository;
import com.orderpilot.domain.intake.ProcessingJob;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import com.orderpilot.domain.validation.AiValidationHandoffRepository;
import com.orderpilot.support.DatabaseIntegrationTestBase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * OP-CAP-30 — PostgreSQL concurrency proof for worker result drain idempotency (the residual hardening
 * OP-CAP-29 explicitly deferred). OP-CAP-29 proved the {@code FOR UPDATE SKIP LOCKED} <em>claim</em>
 * race and only sequential duplicate-drain idempotency; this class proves the result-drain side under
 * genuine concurrency: duplicate, conflicting, stale, and cross-tenant result submissions racing for
 * the same job.
 *
 * <p>The fix under test is a tenant-scoped pessimistic row lock on the job
 * ({@code ProcessingJobRepository.findWithLockByIdAndTenantId} → {@code FOR UPDATE}) taken at the start
 * of {@link AiWorkerResultIntakeService#intake}. It serializes racing drains on the database row so
 * exactly one caller performs the terminal transition + advisory run/result + success audit + validation
 * handoff event, and every racing duplicate observes the committed run and is absorbed as a deterministic
 * idempotent duplicate. These tests would FAIL against the previous naive read-then-insert intake (which
 * locked nothing): two racing callers would both pass the existence check and both insert/transition.
 *
 * <p>Like the other {@code *PostgresIntegrationTest} classes it boots the real application context
 * against a real PostgreSQL (Testcontainers) with Flyway, runs each drain on its own pooled connection,
 * and is SKIPPED (not errored) when no Docker daemon is present so the default H2 suite stays green.1
 */
@Testcontainers
@EnabledIf("dockerAvailable")
@Sql(scripts = {DatabaseIntegrationTestBase.CLEAN, DatabaseIntegrationTestBase.TENANTS})
class WorkerResultDrainConcurrencyPostgresIntegrationTest extends DatabaseIntegrationTestBase {

  static boolean dockerAvailable() {
    return DockerClientFactory.instance().isDockerAvailable();
  }

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    // Each concurrent drain holds its own connection while blocked on the job row lock, plus a
    // REQUIRES_NEW audit write (and the winner an AFTER_COMMIT handoff) — size generously to avoid
    // pool starvation while the losers queue behind the winner's lock.
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> 16);
  }

  private static final int DRAINERS = 6;
  private static final Instant BASE = Instant.parse("2026-06-16T12:00:00Z");

  @Autowired private WorkerJobLeaseService leaseService;
  @Autowired private AiWorkerResultIntakeService intakeService;
  @Autowired private ProcessingJobRepository jobRepository;
  @Autowired private ExtractionRunRepository runRepository;
  @Autowired private ExtractionResultRepository resultRepository;
  @Autowired private AiValidationHandoffRepository handoffRepository;
  @Autowired private AuditEventRepository auditEventRepository;

  // ----------------------------------------------------------------------------------------------
  // 1. Concurrent duplicate SUCCESS drain: N racing identical results -> exactly one terminal commit,
  //    one advisory run/result, one success audit; the rest are deterministic idempotent duplicates.
  // ----------------------------------------------------------------------------------------------
  @Test
  void concurrentDuplicateSuccessDrainAppliesExactlyOnce() throws Exception {
    ProcessingJob job = leaseAsProcessing(TENANT_A);
    AiProcessingResultIntakeRequest request = successResult(job);

    List<Outcome> outcomes = runConcurrentDrains(TENANT_A, () -> request);

    assertThat(outcomes).hasSize(DRAINERS);
    assertThat(outcomes).allMatch(Outcome::ok); // no drain errored
    assertThat(outcomes.stream().filter(o -> !o.duplicate).count()).isEqualTo(1); // exactly one winner
    assertThat(outcomes.stream().filter(o -> o.duplicate).count()).isEqualTo(DRAINERS - 1L); // rest idempotent

    // Exactly one terminal transition and one advisory side-effect set survived the race.
    assertThat(jobRepository.findByIdAndTenantId(job.getId(), TENANT_A).orElseThrow().getStatus())
        .isEqualTo("SUCCEEDED");
    assertThat(runRepository.count()).isEqualTo(1);
    assertThat(resultRepository.count()).isEqualTo(1);

    // Side-effect idempotency: exactly one success audit, and one duplicate audit per losing drain.
    assertThat(countAudit(TENANT_A, "ai_processing_result.intake_succeeded")).isEqualTo(1);
    assertThat(countAudit(TENANT_A, "ai_processing_result.intake_duplicate")).isEqualTo(DRAINERS - 1L);
  }

  // ----------------------------------------------------------------------------------------------
  // 2. Concurrent CONFLICTING drain: success + failure results race for the same job -> exactly one
  //    terminal result is committed and the final state cannot oscillate; same-result losers are
  //    idempotent duplicates, while conflicting terminal losers are rejected.
  // ----------------------------------------------------------------------------------------------
  @Test
  void concurrentConflictingDrainCommitsExactlyOneTerminalResult() throws Exception {
    ProcessingJob job = leaseAsProcessing(TENANT_A);
    AiProcessingResultIntakeRequest success = successResult(job);
    AiProcessingResultIntakeRequest failure = failureResult(job);

    // Alternate success/failure across the drainers so both outcomes genuinely contend for the win.
    List<Outcome> outcomes = runConcurrentDrains(
        TENANT_A, i -> (i % 2 == 0) ? success : failure);

    assertThat(outcomes).hasSize(DRAINERS);
    List<Outcome> successfulCommits = outcomes.stream().filter(Outcome::terminalCommit).toList();
    List<Outcome> duplicateReplays = outcomes.stream().filter(Outcome::duplicateReplay).toList();
    List<Outcome> rejectedConflicts = outcomes.stream().filter(Outcome::conflict).toList();
    List<Outcome> unexpected = outcomes.stream().filter(o ->
        !o.terminalCommit() && !o.duplicateReplay() && !o.conflict()).toList();
    assertThat(unexpected).isEmpty();
    assertThat(successfulCommits).hasSize(1); // exactly one winner
    assertThat(duplicateReplays).isNotEmpty(); // same terminal result replay remains idempotent
    assertThat(rejectedConflicts).isNotEmpty();

    // Exactly one advisory run committed; the job's terminal status matches that single winning run and
    // is itself terminal (never PENDING/PROCESSING) — the state cannot oscillate between success/failure.
    assertThat(runRepository.count()).isEqualTo(1);
    assertThat(resultRepository.count()).isEqualTo(1);
    String runStatus = runRepository.findAll().get(0).getStatus();
    String jobStatus = jobRepository.findByIdAndTenantId(job.getId(), TENANT_A).orElseThrow().getStatus();
    assertThat(jobStatus).isIn("SUCCEEDED", "FAILED");
    assertThat(jobStatus).isEqualTo("SUCCEEDED".equals(runStatus) ? "SUCCEEDED" : "FAILED");
    assertThat(countAudit(TENANT_A, "ai_processing_result.intake_succeeded")).isEqualTo(1);
    assertThat(countAudit(TENANT_A, "ai_processing_result.intake_duplicate")).isEqualTo(duplicateReplays.size());
  }

  // ----------------------------------------------------------------------------------------------
  // 3. Stale attempt/lease result cannot overwrite newer terminal state. The job is claimed, then its
  //    lease goes stale and the reaper moves it to terminal FAILED; a late SUCCESS from the dead lease
  //    must be rejected and must not resurrect or mutate the job, and must create no advisory run.
  // ----------------------------------------------------------------------------------------------
  @Test
  void staleLeaseResultCannotOverwriteTerminalState() {
    ProcessingJob job = newPendingJob(TENANT_A);

    TenantContext.setTenantId(TENANT_A);
    try {
      leaseService.claim(1); // -> PROCESSING

      ProcessingJob processing =
          jobRepository.findByIdAndTenantId(job.getId(), TENANT_A).orElseThrow();

      assertThat(processing.getStatus()).isEqualTo("PROCESSING");
      assertThat(processing.getStartedAt()).isNotNull();

      Instant staleCutoff = processing.getStartedAt().plusSeconds(1);

      int recovered = leaseService.recoverStaleProcessing(staleCutoff, null);

      assertThat(recovered).isEqualTo(1);

      ProcessingJob failed =
          jobRepository.findByIdAndTenantId(job.getId(), TENANT_A).orElseThrow();

      assertThat(failed.getStatus()).isEqualTo("FAILED");

      Outcome late = drainOnce(TENANT_A, successResult(job));

      assertThat(late.ok()).isFalse();
      assertThat(late.error).contains("job_not_in_processable_state");

      ProcessingJob afterLateResult =
          jobRepository.findByIdAndTenantId(job.getId(), TENANT_A).orElseThrow();

      assertThat(afterLateResult.getStatus()).isEqualTo("FAILED");
      assertThat(runRepository.count()).isZero();
      assertThat(resultRepository.count()).isZero();
    } finally {
      TenantContext.clear();
    }
  }

  // ----------------------------------------------------------------------------------------------
  // 4. Stale recovery and result intake both write terminal job state. They must serialize on the
  //    processing_job row so the final job status can never disagree with advisory run/result side
  //    effects.
  // ----------------------------------------------------------------------------------------------
  @Test
void staleRecoveryAndResultIntakeRaceIsSerialized() throws Exception {
  ProcessingJob job = staleProcessingJob(TENANT_A);
  AiProcessingResultIntakeRequest request = successResult(job);

  ProcessingJob processing =
      jobRepository.findByIdAndTenantId(job.getId(), TENANT_A).orElseThrow();

  assertThat(processing.getStatus()).isEqualTo("PROCESSING");
  assertThat(processing.getStartedAt()).isNotNull();

  Instant staleCutoff = processing.getStartedAt().plusSeconds(1);

  RaceResult race = runRecoveryAndIntakeRace(TENANT_A, request, staleCutoff);

  ProcessingJob fresh = jobRepository.findByIdAndTenantId(job.getId(), TENANT_A).orElseThrow();
  assertThat(fresh.getStatus()).isIn("SUCCEEDED", "FAILED");
  assertThat(runRepository.count()).isLessThanOrEqualTo(1);
  assertThat(resultRepository.count()).isLessThanOrEqualTo(1);
  assertThat(countAudit(TENANT_A, "ai_processing_result.intake_succeeded")).isLessThanOrEqualTo(1);

  if ("SUCCEEDED".equals(fresh.getStatus())) {
    assertThat(race.intake().ok()).isTrue();
    assertThat(runRepository.count()).isEqualTo(1);
    assertThat(resultRepository.count()).isEqualTo(1);
    assertThat(runRepository.findAll().get(0).getStatus()).isEqualTo("SUCCEEDED");
    assertThat(countAudit(TENANT_A, "ai_processing_result.intake_succeeded")).isEqualTo(1);
    assertThat(race.recovery().recovered()).isZero();
  } else {
    assertThat(race.recovery().ok()).isTrue();
    assertThat(race.recovery().recovered()).isEqualTo(1);
    assertThat(runRepository.count()).isZero();
    assertThat(resultRepository.count()).isZero();
    assertThat(countAudit(TENANT_A, "ai_processing_result.intake_succeeded")).isZero();
    assertThat(race.intake().ok()).isFalse();
    assertThat(race.intake().error()).contains("job_not_in_processable_state");
  }
}

  // ----------------------------------------------------------------------------------------------
  // 5. Cross-tenant drain guard under concurrency: tenant B drains racing on tenant A's job (by id) are
  //    all denied as tenant-correlation mismatches and never mutate tenant A's job or tenant B state.
  // ----------------------------------------------------------------------------------------------
  @Test
  void concurrentCrossTenantDrainIsDeniedAndDoesNotMutate() throws Exception {
    ProcessingJob job = leaseAsProcessing(TENANT_A); // PROCESSING under tenant A
    AiProcessingResultIntakeRequest request = successResult(job);

    List<Outcome> outcomes = runConcurrentDrains(TENANT_B, () -> request); // wrong tenant context

    assertThat(outcomes).hasSize(DRAINERS);
    List<Outcome> successfulCommits = outcomes.stream().filter(Outcome::terminalCommit).toList();
    List<Outcome> duplicateReplays = outcomes.stream().filter(Outcome::duplicateReplay).toList();
    List<Outcome> rejectedTenantMismatches = outcomes.stream().filter(Outcome::tenantMismatch).toList();
    List<Outcome> unexpected = outcomes.stream().filter(o -> !o.tenantMismatch()).toList();
    assertThat(successfulCommits).isEmpty();
    assertThat(duplicateReplays).isEmpty();
    assertThat(rejectedTenantMismatches).hasSize(DRAINERS);
    assertThat(unexpected).isEmpty();

    assertThat(jobRepository.findByIdAndTenantId(job.getId(), TENANT_A).orElseThrow().getStatus())
        .isEqualTo("PROCESSING"); // owner's job untouched
    assertThat(runRepository.count()).isZero(); // no advisory run created for anyone
    assertThat(resultRepository.count()).isZero();
    assertThat(handoffRepository.count()).isZero();
    assertThat(runRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_B)).isEmpty();
    assertThat(resultRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_B)).isEmpty();
    assertThat(handoffRepository.findByTenantIdOrderByUpdatedAtDesc(TENANT_B, PageRequest.of(0, 1))).isEmpty();
    assertThat(auditEventRepository.findByTenantIdOrderByOccurredAtDesc(TENANT_B)).isEmpty();
  }

  // ----------------------------------------- helpers -----------------------------------------

  private record Outcome(boolean duplicate, String error) {
    boolean ok() {
      return error == null;
    }

    boolean terminalCommit() {
      return ok() && !duplicate;
    }

    boolean duplicateReplay() {
      return ok() && duplicate;
    }

    boolean conflict() {
      return error != null && error.contains("conflicting_terminal_result");
    }

    boolean tenantMismatch() {
      return error != null && error.contains("tenant_correlation_mismatch");
    }
  }

  private interface RequestFor {
    AiProcessingResultIntakeRequest apply(int index);
  }

  /** Runs {@code DRAINERS} intake calls that all fire together (start gate) under {@code tenant}. */
  private List<Outcome> runConcurrentDrains(UUID tenant, RequestFor requestFor) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(DRAINERS);
    try {
      CountDownLatch ready = new CountDownLatch(DRAINERS);
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Outcome>> futures = new ArrayList<>();
      for (int i = 0; i < DRAINERS; i++) {
        final AiProcessingResultIntakeRequest request = requestFor.apply(i);
        Callable<Outcome> drain = () -> {
          ready.countDown();
          start.await(10, TimeUnit.SECONDS);
          return drainOnce(tenant, request);
        };
        futures.add(pool.submit(drain));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown(); // release all drainers simultaneously
      List<Outcome> outcomes = new ArrayList<>();
      for (Future<Outcome> future : futures) {
        outcomes.add(future.get(30, TimeUnit.SECONDS)); // bounded wait, never infinite
      }
      return outcomes;
    } finally {
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private List<Outcome> runConcurrentDrains(UUID tenant, java.util.function.Supplier<AiProcessingResultIntakeRequest> request)
      throws Exception {
    return runConcurrentDrains(tenant, i -> request.get());
  }

  private RaceResult runRecoveryAndIntakeRace(
    UUID tenant,
    AiProcessingResultIntakeRequest request,
    Instant staleCutoff
  ) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch start = new CountDownLatch(1);
      Future<RecoveryOutcome> recovery = pool.submit(() -> {
        ready.countDown();
        start.await(10, TimeUnit.SECONDS);
        try {
          return new RecoveryOutcome(leaseService.recoverStaleProcessing(staleCutoff, 10), null);
        } catch (RuntimeException ex) {
          return new RecoveryOutcome(0, ex.getMessage());
        }
      });
      Future<Outcome> intake = pool.submit(() -> {
        ready.countDown();
        start.await(10, TimeUnit.SECONDS);
        return drainOnce(tenant, request);
      });

    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    return new RaceResult(recovery.get(30, TimeUnit.SECONDS), intake.get(30, TimeUnit.SECONDS));
  } finally {
    pool.shutdownNow();
    assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
  }
}

  private Outcome drainOnce(UUID tenant, AiProcessingResultIntakeRequest request) {
    TenantContext.setTenantId(tenant);
    try {
      AiProcessingResultIntakeResponse response = intakeService.intake(request);
      return new Outcome(response.duplicate(), null);
    } catch (RuntimeException ex) {
      return new Outcome(false, ex.getMessage());
    } finally {
      TenantContext.clear();
    }
  }

  private ProcessingJob leaseAsProcessing(UUID tenant) {
    ProcessingJob job = newPendingJob(tenant);
    TenantContext.setTenantId(tenant);
    try {
      leaseService.claim(1); // PENDING -> PROCESSING
    } finally {
      TenantContext.clear();
    }
    return job;
  }

  private ProcessingJob newPendingJob(UUID tenant) {
    return jobRepository.saveAndFlush(
        new ProcessingJob(tenant, "DOCUMENT_EXTRACTION", "CHANNEL_MESSAGE", UUID.randomUUID(), 100, BASE));
  }

  private ProcessingJob staleProcessingJob(UUID tenant) {
    ProcessingJob job = newPendingJob(tenant);
    job.markProcessing(BASE.minusSeconds(3_600));
    return jobRepository.saveAndFlush(job);
  }

  private long countAudit(UUID tenant, String action) {
    return auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenant).stream()
        .map(AuditEvent::getAction).filter(action::equals).count();
  }

  private AiProcessingResultIntakeRequest successResult(ProcessingJob job) {
    return result(job, "SUCCEEDED", null);
  }

  private AiProcessingResultIntakeRequest failureResult(ProcessingJob job) {
    return result(job, "FAILED", "worker_reported_failure");
  }

  private AiProcessingResultIntakeRequest result(ProcessingJob job, String status, String safeReason) {
    return new AiProcessingResultIntakeRequest(
        job.getId(), job.getTenantId().toString(), job.getTargetType(), job.getTargetId(), status,
        Map.of("detected_intent", "RFQ", "document_type", "message", "overall_confidence", 0.82, "advisory_only", true),
        List.of(), List.of(), List.of(),
        Map.of("provider_name", "rule-based-understanding", "mode", "RULE_BASED"),
        "op-cap-07c.v1", BASE, BASE.plusMillis(10), 10L, safeReason);
  }

  private record RaceResult(RecoveryOutcome recovery, Outcome intake) {}

  private record RecoveryOutcome(int recovered, String error) {
    boolean ok() {
      return error == null;
    }
  }
}
