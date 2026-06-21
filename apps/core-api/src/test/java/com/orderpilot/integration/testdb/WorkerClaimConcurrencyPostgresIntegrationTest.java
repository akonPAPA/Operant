package com.orderpilot.integration.testdb;

import static com.orderpilot.support.TestTenantFixtures.TENANT_A;
import static com.orderpilot.support.TestTenantFixtures.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.AiResultIntakeDtos.AiProcessingResultIntakeRequest;
import com.orderpilot.api.dto.AiResultIntakeDtos.AiProcessingResultIntakeResponse;
import com.orderpilot.application.services.WorkerJobLeaseService;
import com.orderpilot.application.services.extraction.AiWorkerResultIntakeService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.ExtractionResultRepository;
import com.orderpilot.domain.extraction.ExtractionRunRepository;
import com.orderpilot.domain.intake.ProcessingJob;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import com.orderpilot.support.DatabaseIntegrationTestBase;
import com.orderpilot.support.RequiresPostgresIntegration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * OP-CAP-29 — PostgreSQL concurrency proof for the worker job-claim lease (FOR UPDATE SKIP LOCKED).
 *
 * <p>The fast H2 suite ({@code WorkerRuntimeLifecycleStage29Test}) cannot prove DB-level lease
 * exclusivity: H2 degrades SKIP LOCKED to plain {@code FOR UPDATE} and {@code @DataJpaTest} runs in a
 * single shared transaction/connection. This test boots the real application context against a real
 * PostgreSQL (Testcontainers), runs Flyway for the production schema, and drives 6 genuinely concurrent
 * claim transactions (each on its own pooled connection) so the {@code FOR UPDATE SKIP LOCKED} path is
 * actually exercised.
 *
 * <p>It reuses the existing {@code integration-test} convention (profile + {@code @Sql} seed scripts +
 * tenant fixtures) but overrides only the datasource to point at the container, so it is self-contained
 * and needs no external local Postgres — only Docker. Like the other {@code *PostgresIntegrationTest}
 * classes it does not run under the default H2 unit suite.
 *
 * <p>Pool sizing: each claim transaction additionally triggers a {@code REQUIRES_NEW} audit write
 * (AuditEventService) that holds a second connection while the claim's own connection is suspended, so
 * peak demand is ~2× the worker count (6 workers → up to 12 connections). Hikari is sized to 16 to
 * avoid pool starvation/deadlock under that pattern.
 */
@Testcontainers
@RequiresPostgresIntegration
@EnabledIf("dockerAvailable")
@Sql(scripts = {DatabaseIntegrationTestBase.CLEAN, DatabaseIntegrationTestBase.TENANTS})
class WorkerClaimConcurrencyPostgresIntegrationTest extends DatabaseIntegrationTestBase {

  // Evaluated before any extension callback, so the whole class is SKIPPED (not errored) when no Docker
  // daemon is present — keeping the default H2 suite green on machines/CI lanes without Docker.
  static boolean dockerAvailable() {
    return DockerClientFactory.instance().isDockerAvailable();
  }

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    // Override only the connection — keep the integration-test profile's safe gating (external
    // execution / connectors / bot disabled, Flyway on, no embedded-DB replacement).
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    // Enough for 6 concurrent claims each suspending into a REQUIRES_NEW audit write (~12) + headroom.
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> 16);
  }

  private static final int WORKERS = 6;
  private static final Instant BASE = Instant.parse("2026-06-16T12:00:00Z");

  @Autowired private WorkerJobLeaseService leaseService;
  @Autowired private AiWorkerResultIntakeService intakeService;
  @Autowired private ProcessingJobRepository jobRepository;
  @Autowired private ExtractionRunRepository runRepository;
  @Autowired private ExtractionResultRepository resultRepository;

  // ----------------------------------------------------------------------------------------------
  // 1. Single-job contention: 1 job, 6 workers, limit 1 -> exactly one winner.
  // ----------------------------------------------------------------------------------------------
  @Test
  void singleJobContentionGivesExactlyOneWinner() throws Exception {
    UUID jobId = newPendingJob(TENANT_A, 0).getId();

    List<List<UUID>> results = runConcurrentClaims(TENANT_A, 1);

    List<UUID> claimed = flatten(results);
    assertThat(claimed).containsExactly(jobId); // exactly one worker claimed, exactly once
    assertThat(results.stream().filter(r -> !r.isEmpty()).count()).isEqualTo(1);
    assertThat(results.stream().filter(List::isEmpty).count()).isEqualTo(WORKERS - 1);
    assertThat(countByStatus(TENANT_A, "PROCESSING")).isEqualTo(1);
    assertThat(countByStatus(TENANT_A, "PENDING")).isZero();
  }

  // ----------------------------------------------------------------------------------------------
  // 2. Equal jobs/workers: 6 jobs, 6 workers, limit 1 -> all claimed ids unique, no double-own.
  // ----------------------------------------------------------------------------------------------
  @Test
  void equalJobsAndWorkersProduceUniqueDisjointClaims() throws Exception {
    Set<UUID> seeded = new HashSet<>();
    for (int i = 0; i < WORKERS; i++) {
      seeded.add(newPendingJob(TENANT_A, i).getId());
    }
    UUID foreignJob = newPendingJob(TENANT_B, 0).getId();

    List<List<UUID>> results = runConcurrentClaims(TENANT_A, 1);

    List<UUID> claimed = flatten(results);
    assertThat(claimed).doesNotHaveDuplicates(); // no job claimed by two workers
    assertThat(new HashSet<>(claimed)).isSubsetOf(seeded); // only tenant A's jobs
    assertThat(claimed).doesNotContain(foreignJob); // never the other tenant's job
    assertThat(claimed).hasSizeLessThanOrEqualTo(WORKERS);
  }

  // ----------------------------------------------------------------------------------------------
  // 3. Batch claim contention: 30 jobs, 6 workers, limit 5 -> disjoint batches, no duplicate ids.
  // ----------------------------------------------------------------------------------------------
  @Test
  void batchClaimsAreDisjointAndOrderedWithinBatch() throws Exception {
    int totalJobs = 30;
    for (int i = 0; i < totalJobs; i++) {
      newPendingJob(TENANT_A, i);
    }

    List<List<UUID>> results = runConcurrentClaims(TENANT_A, 5);

    List<UUID> claimed = flatten(results);
    assertThat(claimed).doesNotHaveDuplicates(); // batches are disjoint
    assertThat(claimed).hasSizeLessThanOrEqualTo(totalJobs);
    // Oldest-first holds deterministically WITHIN each worker's own batch (a single locked query).
    for (List<UUID> batch : results) {
      assertThat(batch).hasSizeLessThanOrEqualTo(5);
      assertBatchOldestFirst(batch);
    }
    assertThat(countByStatus(TENANT_A, "PROCESSING")).isEqualTo(claimed.size());
  }

  // ----------------------------------------------------------------------------------------------
  // 4. Cross-tenant guard: workers under tenant A never see tenant B's jobs.
  // ----------------------------------------------------------------------------------------------
  @Test
  void tenantAWorkersNeverClaimTenantBJobs() throws Exception {
    Set<UUID> tenantBJobs = new HashSet<>();
    for (int i = 0; i < WORKERS; i++) {
      newPendingJob(TENANT_A, i);
      tenantBJobs.add(newPendingJob(TENANT_B, i).getId());
    }

    List<List<UUID>> results = runConcurrentClaims(TENANT_A, 5);

    List<UUID> claimed = flatten(results);
    assertThat(claimed).doesNotHaveDuplicates();
    assertThat(claimed).noneMatch(tenantBJobs::contains); // strict tenant isolation under contention
    assertThat(countByStatus(TENANT_B, "PENDING")).isEqualTo(WORKERS); // tenant B untouched
  }

  // ----------------------------------------------------------------------------------------------
  // 5. Duplicate result drain idempotency (SEQUENTIAL). extraction_run has no DB unique constraint on
  // (tenant, processing_job, provider_type), so true CONCURRENT duplicate drain is intentionally NOT
  // claimed here (see final report). This proves the read-then-insert idempotency for serial redelivery.
  // ----------------------------------------------------------------------------------------------
  @Test
  void duplicateSequentialDrainIsAppliedOnlyOnce() throws Exception {
    ProcessingJob job = newPendingJob(TENANT_A, 0);
    TenantContext.setTenantId(TENANT_A);
    leaseService.claim(1); // -> PROCESSING
    TenantContext.clear();

    AiProcessingResultIntakeRequest request = successResult(job);

    int redeliveries = 6;
    int duplicates = 0;
    for (int i = 0; i < redeliveries; i++) {
      TenantContext.setTenantId(TENANT_A);
      try {
        AiProcessingResultIntakeResponse response = intakeService.intake(request);
        if (response.duplicate()) {
          duplicates++;
        }
      } finally {
        TenantContext.clear();
      }
    }

    assertThat(duplicates).isEqualTo(redeliveries - 1); // first applies, the rest are no-ops
    assertThat(runRepository.count()).isEqualTo(1); // exactly one ExtractionRun
    assertThat(resultRepository.count()).isEqualTo(1); // exactly one ExtractionResult
    assertThat(countByStatus(TENANT_A, "SUCCEEDED")).isEqualTo(1);
  }

  // ----------------------------------------- helpers -----------------------------------------

  /** Runs {@code WORKERS} claim calls that all fire together (start gate) under {@code tenant}. */
  private List<List<UUID>> runConcurrentClaims(UUID tenant, int limit) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
    try {
      CountDownLatch ready = new CountDownLatch(WORKERS);
      CountDownLatch start = new CountDownLatch(1);
      List<Future<List<UUID>>> futures = new ArrayList<>();
      for (int i = 0; i < WORKERS; i++) {
        Callable<List<UUID>> worker = () -> {
          ready.countDown();
          start.await(10, TimeUnit.SECONDS);
          TenantContext.setTenantId(tenant);
          try {
            return leaseService.claim(limit).stream().map(ProcessingJob::getId).toList();
          } finally {
            TenantContext.clear();
          }
        };
        futures.add(pool.submit(worker));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown(); // release all workers simultaneously
      List<List<UUID>> results = new ArrayList<>();
      for (Future<List<UUID>> future : futures) {
        results.add(future.get(20, TimeUnit.SECONDS)); // bounded wait, never infinite
      }
      return results;
    } finally {
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private ProcessingJob newPendingJob(UUID tenant, int ageOffset) {
    // Older jobs get an earlier queued_at so oldest-first ordering is deterministic per batch.
    Instant queuedAt = BASE.minusSeconds(1000L - ageOffset);
    return jobRepository.saveAndFlush(
        new ProcessingJob(tenant, "DOCUMENT_EXTRACTION", "CHANNEL_MESSAGE", UUID.randomUUID(), 100, queuedAt));
  }

  private void assertBatchOldestFirst(List<UUID> batch) {
    Instant previous = null;
    for (UUID id : batch) {
      Instant queuedAt = jobRepository.findById(id).orElseThrow().getQueuedAt();
      if (previous != null) {
        assertThat(queuedAt).isAfterOrEqualTo(previous);
      }
      previous = queuedAt;
    }
  }

  private long countByStatus(UUID tenant, String status) {
    return jobRepository.countByTenantIdAndStatus(tenant, status);
  }

  private static List<UUID> flatten(List<List<UUID>> results) {
    return results.stream().flatMap(List::stream).toList();
  }

  private AiProcessingResultIntakeRequest successResult(ProcessingJob job) {
    return new AiProcessingResultIntakeRequest(
        job.getId(), job.getTenantId().toString(), job.getTargetType(), job.getTargetId(), "SUCCEEDED",
        Map.of("detected_intent", "RFQ", "document_type", "message", "overall_confidence", 0.82, "advisory_only", true),
        List.of(), List.of(), List.of(),
        Map.of("provider_name", "rule-based-understanding", "mode", "RULE_BASED"),
        "op-cap-07c.v1", BASE, BASE.plusMillis(10), 10L, null);
  }
}
