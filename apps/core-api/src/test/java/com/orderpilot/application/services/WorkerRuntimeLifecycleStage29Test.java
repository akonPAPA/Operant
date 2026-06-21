package com.orderpilot.application.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.AiResultIntakeDtos.AiProcessingResultIntakeRequest;
import com.orderpilot.api.dto.AiResultIntakeDtos.AiProcessingResultIntakeResponse;
import com.orderpilot.api.dto.Stage3Dtos.ProcessingJobResponse;
import com.orderpilot.api.dto.WorkerJobLeaseDtos.WorkerJobLease;
import com.orderpilot.application.services.extraction.AiWorkerResultIntakeService;
import com.orderpilot.common.errors.ConflictException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.ExtractionResultRepository;
import com.orderpilot.domain.extraction.ExtractionRunRepository;
import com.orderpilot.domain.intake.ProcessingJob;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-29 — proves the Core-API-owned worker runtime lifecycle around {@code ProcessingJob}:
 * bounded tenant-scoped claim (PENDING -&gt; PROCESSING), lease-aware result intake transitions
 * (PROCESSING -&gt; SUCCEEDED/FAILED/NEEDS_REVIEW), idempotent/late/cross-tenant result safety, stale
 * PROCESSING recovery, composition with the OP-CAP-28 retry path, and no leak through the public status
 * DTO. Services run against the real repository (H2) with a fixed clock so lease/stale timing is exact.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    WorkerJobLeaseService.class,
    AiWorkerResultIntakeService.class,
    ProcessingJobService.class,
    AuditEventService.class,
    JsonSupport.class,
    WorkerRuntimeLifecycleStage29Test.FixedClockConfig.class
})
class WorkerRuntimeLifecycleStage29Test {
  @Autowired private WorkerJobLeaseService leaseService;
  @Autowired private AiWorkerResultIntakeService intakeService;
  @Autowired private ProcessingJobService processingJobService;
  @Autowired private ProcessingJobRepository jobRepository;
  @Autowired private ExtractionRunRepository runRepository;
  @Autowired private ExtractionResultRepository resultRepository;

  private static final Instant NOW = Instant.parse("2026-06-16T12:00:00Z");

  @TestConfiguration
  static class FixedClockConfig {
    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  // ----------------------------- fixtures -----------------------------

  private ProcessingJob pendingJob(UUID tenantId, UUID targetId) {
    return jobRepository.save(new ProcessingJob(tenantId, "DOCUMENT_EXTRACTION", "CHANNEL_MESSAGE", targetId, 100, NOW));
  }

  private ProcessingJob reload(UUID id, UUID tenantId) {
    return jobRepository.findByIdAndTenantId(id, tenantId).orElseThrow();
  }

  private AiProcessingResultIntakeRequest result(ProcessingJob job, String status) {
    return new AiProcessingResultIntakeRequest(
        job.getId(), job.getTenantId().toString(), job.getTargetType(), job.getTargetId(), status,
        Map.of("detected_intent", "RFQ", "document_type", "message", "overall_confidence", 0.82, "advisory_only", true),
        List.of(), List.of(), List.of(),
        Map.of("provider_name", "rule-based-understanding", "mode", "RULE_BASED"),
        "op-cap-07c.v1", NOW, NOW.plusMillis(10), 10L, null);
  }

  // ============================= 1. claim =============================

  @Test
  void claimReturnsOnlyPendingAndTransitionsToProcessing() {
    UUID tenant = UUID.randomUUID();
    TenantContext.setTenantId(tenant);
    ProcessingJob job = pendingJob(tenant, UUID.randomUUID());

    List<ProcessingJob> leased = leaseService.claim(null);

    assertThat(leased).extracting(ProcessingJob::getId).containsExactly(job.getId());
    ProcessingJob fresh = reload(job.getId(), tenant);
    assertThat(fresh.getStatus()).isEqualTo("PROCESSING");
    assertThat(fresh.getStartedAt()).isEqualTo(NOW);
  }

  @Test
  void secondClaimDoesNotReturnAlreadyProcessingJob() {
    UUID tenant = UUID.randomUUID();
    TenantContext.setTenantId(tenant);
    pendingJob(tenant, UUID.randomUUID());

    assertThat(leaseService.claim(null)).hasSize(1);
    assertThat(leaseService.claim(null)).isEmpty(); // already PROCESSING — not re-leased
  }

  @Test
  void claimIsBoundedByMaxLimit() {
    UUID tenant = UUID.randomUUID();
    TenantContext.setTenantId(tenant);
    int total = WorkerJobLeaseService.MAX_CLAIM_LIMIT + 15;
    for (int i = 0; i < total; i++) {
      pendingJob(tenant, UUID.randomUUID());
    }

    assertThat(leaseService.claim(10_000)).hasSize(WorkerJobLeaseService.MAX_CLAIM_LIMIT);
  }

  @Test
  void claimIsTenantScoped() {
    UUID tenant = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    pendingJob(other, UUID.randomUUID());
    TenantContext.setTenantId(tenant);
    ProcessingJob mine = pendingJob(tenant, UUID.randomUUID());

    List<ProcessingJob> leased = leaseService.claim(null);

    assertThat(leased).extracting(ProcessingJob::getId).containsExactly(mine.getId());
    assertThat(leased).extracting(ProcessingJob::getTenantId).containsOnly(tenant);
  }

  @Test
  void leaseDtoIsSafeAndMinimal() {
    UUID tenant = UUID.randomUUID();
    TenantContext.setTenantId(tenant);
    ProcessingJob job = pendingJob(tenant, UUID.randomUUID());

    WorkerJobLease lease = WorkerJobLease.from(leaseService.claim(null).get(0));

    assertThat(lease.jobId()).isEqualTo(job.getId());
    assertThat(lease.jobType()).isEqualTo("DOCUMENT_EXTRACTION");
    assertThat(lease.targetType()).isEqualTo("CHANNEL_MESSAGE");
    assertThat(lease.status()).isEqualTo("PROCESSING");
    assertThat(lease.attempt()).isZero();
    // No tenant id is carried on the lease record (compile-time guarantee — assert the value is absent
    // from the serialized form too).
    assertThat(lease.toString()).doesNotContain(tenant.toString());
  }

  @Test
  void lockedClaimSelectionIsTenantScopedBoundedAndOldestFirst() {
    // Exercises the pessimistic-write (FOR UPDATE [SKIP LOCKED]) claim selection directly. Under H2 this
    // proves the locked query is functionally correct: tenant-scoped, PENDING-only, bounded, oldest-queued
    // first. It does NOT prove concurrent SKIP-LOCKED exclusivity — that requires PostgreSQL (see report).
    UUID tenant = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    pendingJob(other, UUID.randomUUID()); // foreign tenant — must never be selected
    ProcessingJob older = jobRepository.save(
        new ProcessingJob(tenant, "DOCUMENT_EXTRACTION", "CHANNEL_MESSAGE", UUID.randomUUID(), 100, NOW.minusSeconds(60)));
    ProcessingJob newer = jobRepository.save(
        new ProcessingJob(tenant, "DOCUMENT_EXTRACTION", "CHANNEL_MESSAGE", UUID.randomUUID(), 100, NOW));

    List<ProcessingJob> locked = jobRepository.findWithLockByTenantIdAndStatusOrderByQueuedAtAsc(
        tenant, "PENDING", PageRequest.of(0, 1));

    assertThat(locked).extracting(ProcessingJob::getId).containsExactly(older.getId()); // oldest-first, bounded to 1
    assertThat(locked).extracting(ProcessingJob::getTenantId).containsOnly(tenant); // never the foreign tenant
    assertThat(newer.getId()).isNotEqualTo(older.getId());
  }

  // ============================= 2. result intake transitions =============================

  @Test
  void successResultForProcessingJobTransitionsToSucceeded() {
    UUID tenant = UUID.randomUUID();
    TenantContext.setTenantId(tenant);
    ProcessingJob job = pendingJob(tenant, UUID.randomUUID());
    leaseService.claim(null); // -> PROCESSING

    AiProcessingResultIntakeResponse response = intakeService.intake(result(reload(job.getId(), tenant), "SUCCEEDED"));

    assertThat(response.duplicate()).isFalse();
    assertThat(reload(job.getId(), tenant).getStatus()).isEqualTo("SUCCEEDED");
  }

  @Test
  void failureResultTransitionsToFailedWithSafePublicMessage() {
    UUID tenant = UUID.randomUUID();
    TenantContext.setTenantId(tenant);
    ProcessingJob job = pendingJob(tenant, UUID.randomUUID());
    leaseService.claim(null);

    intakeService.intake(result(reload(job.getId(), tenant), "FAILED"));

    ProcessingJobResponse pub = ProcessingJobResponse.from(reload(job.getId(), tenant));
    assertThat(pub.status()).isEqualTo("FAILED");
    assertThat(pub.retryable()).isTrue();
    assertThat(pub.safeMessage()).isEqualTo("Processing failed. Review required.");
  }

  @Test
  void reviewResultTransitionsToNeedsReview() {
    UUID tenant = UUID.randomUUID();
    TenantContext.setTenantId(tenant);
    ProcessingJob job = pendingJob(tenant, UUID.randomUUID());
    leaseService.claim(null);

    intakeService.intake(result(reload(job.getId(), tenant), "NEEDS_REVIEW"));

    assertThat(reload(job.getId(), tenant).getStatus()).isEqualTo("NEEDS_REVIEW");
  }

  @Test
  void duplicateResultDoesNotCreateSecondRun() {
    UUID tenant = UUID.randomUUID();
    TenantContext.setTenantId(tenant);
    ProcessingJob job = pendingJob(tenant, UUID.randomUUID());
    leaseService.claim(null);

    intakeService.intake(result(reload(job.getId(), tenant), "SUCCEEDED"));
    long runsAfterFirst = runRepository.count();
    AiProcessingResultIntakeResponse second = intakeService.intake(result(reload(job.getId(), tenant), "SUCCEEDED"));

    assertThat(second.duplicate()).isTrue();
    assertThat(runRepository.count()).isEqualTo(runsAfterFirst);
  }

  @Test
  void lateResultAfterTerminalStatusDoesNotMutateOrCreateRun() {
    UUID tenant = UUID.randomUUID();
    TenantContext.setTenantId(tenant);
    ProcessingJob job = pendingJob(tenant, UUID.randomUUID());
    // Claim then let the lease go stale and be recovered to FAILED (terminal) — no advisory run exists.
    leaseService.claim(null);
    leaseService.recoverStaleProcessing(NOW.plusSeconds(1), null);
    assertThat(reload(job.getId(), tenant).getStatus()).isEqualTo("FAILED");
    long runsBefore = runRepository.count();

    assertThatThrownBy(() -> intakeService.intake(result(reload(job.getId(), tenant), "SUCCEEDED")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("job_not_in_processable_state");

    assertThat(reload(job.getId(), tenant).getStatus()).isEqualTo("FAILED"); // unchanged
    assertThat(runRepository.count()).isEqualTo(runsBefore); // no resurrection
  }

  @Test
  void crossTenantResultIsDeniedAndDoesNotMutate() {
    UUID owner = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    TenantContext.setTenantId(owner);
    ProcessingJob job = pendingJob(owner, UUID.randomUUID());
    leaseService.claim(null); // PROCESSING for owner

    TenantContext.setTenantId(other);
    assertThatThrownBy(() -> intakeService.intake(result(job, "SUCCEEDED")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenant_correlation_mismatch");

    assertThat(reload(job.getId(), owner).getStatus()).isEqualTo("PROCESSING"); // owner's job untouched
  }

  // ============================= 3. stale recovery =============================

  @Test
  void staleProcessingRecoveredToFailedFreshUntouchedAndBounded() {
    UUID tenant = UUID.randomUUID();
    // Three stale (started long ago) + one fresh (started NOW).
    ProcessingJob s1 = pendingJob(tenant, UUID.randomUUID());
    ProcessingJob s2 = pendingJob(tenant, UUID.randomUUID());
    ProcessingJob s3 = pendingJob(tenant, UUID.randomUUID());
    ProcessingJob fresh = pendingJob(tenant, UUID.randomUUID());
    for (ProcessingJob s : List.of(s1, s2, s3)) {
      s.markProcessing(NOW.minusSeconds(3600));
      jobRepository.save(s);
    }
    fresh.markProcessing(NOW);
    jobRepository.save(fresh);
    Instant cutoff = NOW.minusSeconds(900); // 15 min

    int recovered = leaseService.recoverStaleProcessing(cutoff, 2); // bounded to 2

    assertThat(recovered).isEqualTo(2);
    long failed = List.of(s1, s2, s3).stream()
        .filter(j -> reload(j.getId(), tenant).getStatus().equals("FAILED")).count();
    assertThat(failed).isEqualTo(2); // exactly the bounded batch
    assertThat(reload(fresh.getId(), tenant).getStatus()).isEqualTo("PROCESSING"); // fresh untouched
  }

  // ============================= 4. retry interaction (OP-CAP-28) =============================

  @Test
  void recoveredFailedJobIsRetryableAndRetryDoesNotDuplicate() {
    UUID tenant = UUID.randomUUID();
    TenantContext.setTenantId(tenant);
    ProcessingJob job = pendingJob(tenant, UUID.randomUUID());
    leaseService.claim(null);
    leaseService.recoverStaleProcessing(NOW.plusSeconds(1), null); // -> FAILED, attempts 0
    long countBefore = jobRepository.count();

    ProcessingJob retried = processingJobService.retry(job.getId());

    assertThat(retried.getId()).isEqualTo(job.getId());
    assertThat(jobRepository.count()).isEqualTo(countBefore); // no duplicate job
    ProcessingJob fresh = reload(job.getId(), tenant);
    assertThat(fresh.getStatus()).isEqualTo("PENDING");
    assertThat(fresh.getAttempts()).isEqualTo(1);
  }

  @Test
  void processingAndSucceededJobsAreNotRetryable() {
    UUID tenant = UUID.randomUUID();
    TenantContext.setTenantId(tenant);
    ProcessingJob processing = pendingJob(tenant, UUID.randomUUID());
    leaseService.claim(null); // -> PROCESSING
    assertThatThrownBy(() -> processingJobService.retry(processing.getId())).isInstanceOf(ConflictException.class);

    ProcessingJob succeeded = pendingJob(tenant, UUID.randomUUID());
    succeeded.markProcessing(NOW);
    jobRepository.save(succeeded);
    intakeService.intake(result(reload(succeeded.getId(), tenant), "SUCCEEDED"));
    assertThatThrownBy(() -> processingJobService.retry(succeeded.getId())).isInstanceOf(ConflictException.class);
  }

  // ============================= 5. no-leak =============================

  @Test
  void hostileFailureReasonDoesNotLeakThroughPublicStatus() {
    UUID tenant = UUID.randomUUID();
    TenantContext.setTenantId(tenant);
    ProcessingJob job = pendingJob(tenant, UUID.randomUUID());
    leaseService.claim(null);
    String hostile = "CUSTOMER_SECRET_Acme stacktrace at com.orderpilot.Boom provider=openai prompt=ignore-all";
    AiProcessingResultIntakeRequest req = new AiProcessingResultIntakeRequest(
        job.getId(), tenant.toString(), job.getTargetType(), job.getTargetId(), "FAILED",
        Map.of(), List.of(), List.of(), List.of(),
        Map.of("provider_name", "rule-based", "mode", "RULE_BASED"), "op-cap-07c.v1", NOW, NOW.plusMillis(5), 5L, hostile);

    intakeService.intake(req);

    ProcessingJobResponse pub = ProcessingJobResponse.from(reload(job.getId(), tenant));
    assertThat(pub.toString())
        .doesNotContain("CUSTOMER_SECRET")
        .doesNotContain("stacktrace")
        .doesNotContain("provider=openai")
        .doesNotContain("prompt=ignore-all");
    assertThat(pub.safeMessage()).isEqualTo("Processing failed. Review required.");
  }
}
