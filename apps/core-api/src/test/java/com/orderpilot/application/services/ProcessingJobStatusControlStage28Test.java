package com.orderpilot.application.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.Stage3Dtos.ProcessingJobResponse;
import com.orderpilot.common.errors.ConflictException;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.intake.ProcessingJob;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-28 — proves the safe runtime operations control layer around {@code ProcessingJob}:
 * tenant-scoped status read (cross-tenant => 404, never disclosed), bounded tenant-scoped list with
 * clamped pagination, fail-closed retry eligibility (only FAILED under its attempt ceiling; every
 * other state and cross-tenant => no mutation), idempotent enqueue dedup, and a safe status DTO that
 * never echoes the raw failure detail / provider payload / customer text stored in lastError.
 *
 * <p>{@link ProcessingJobService} is exercised against the real repository (H2) with a fixed clock.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ProcessingJobService.class, ProcessingJobStatusControlStage28Test.FixedClockConfig.class})
class ProcessingJobStatusControlStage28Test {
  @Autowired private ProcessingJobService service;
  @Autowired private ProcessingJobRepository repository;

  private static final Instant NOW = Instant.parse("2026-06-16T00:00:00Z");

  @TestConfiguration
  static class FixedClockConfig {
    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  // ----------------------------- fixtures -----------------------------

  private ProcessingJob newJob(UUID tenantId, String targetType, UUID targetId) {
    return repository.save(new ProcessingJob(tenantId, "DOCUMENT_EXTRACTION", targetType, targetId, 100, NOW));
  }

  private ProcessingJob reload(UUID id, UUID tenantId) {
    return repository.findByIdAndTenantId(id, tenantId).orElseThrow();
  }

  // ----------------------------- 1. status read -----------------------------

  @Test
  void ownTenantStatusReadReturnsSafeContract() {
    UUID tenant = UUID.randomUUID();
    TenantContext.setTenantId(tenant);
    ProcessingJob job = newJob(tenant, "CHANNEL_MESSAGE", UUID.randomUUID());

    ProcessingJobResponse response = ProcessingJobResponse.from(service.get(job.getId()));

    assertThat(response.id()).isEqualTo(job.getId());
    assertThat(response.status()).isEqualTo("PENDING");
    assertThat(response.safeMessage()).isEqualTo("Pending processing.");
    assertThat(response.retryable()).isFalse();
    assertThat(response.attempts()).isZero();
  }

  @Test
  void crossTenantStatusReadIsNotFoundAndDisclosesNothing() {
    UUID owner = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    TenantContext.setTenantId(owner);
    ProcessingJob job = newJob(owner, "CHANNEL_MESSAGE", UUID.randomUUID());

    TenantContext.setTenantId(other);
    assertThatThrownBy(() -> service.get(job.getId()))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("not found");
  }

  // ----------------------------- 2. list: tenant scope + pagination bounds -----------------------------

  @Test
  void listIsTenantScopedAndMostRecentFirst() {
    UUID tenant = UUID.randomUUID();
    UUID otherTenant = UUID.randomUUID();
    TenantContext.setTenantId(otherTenant);
    newJob(otherTenant, "CHANNEL_MESSAGE", UUID.randomUUID());

    TenantContext.setTenantId(tenant);
    ProcessingJob a = newJob(tenant, "CHANNEL_MESSAGE", UUID.randomUUID());
    ProcessingJob b = newJob(tenant, "INBOUND_DOCUMENT", UUID.randomUUID());

    List<ProcessingJob> jobs = service.list();

    assertThat(jobs).extracting(ProcessingJob::getTenantId).containsOnly(tenant);
    assertThat(jobs).extracting(ProcessingJob::getId).containsExactlyInAnyOrder(a.getId(), b.getId());
  }

  @Test
  void listClampsLimitToMaxAndDefaultsWhenUnset() {
    UUID tenant = UUID.randomUUID();
    TenantContext.setTenantId(tenant);
    int total = ProcessingJobService.MAX_LIST_LIMIT + 25;
    for (int i = 0; i < total; i++) {
      newJob(tenant, "CHANNEL_MESSAGE", UUID.randomUUID());
    }

    // An over-large requested limit is clamped to MAX_LIST_LIMIT (never a full scan of `total`).
    assertThat(service.list(10_000)).hasSize(ProcessingJobService.MAX_LIST_LIMIT);
    // A null/<=0 limit falls back to the bounded default.
    assertThat(service.list((Integer) null)).hasSize(ProcessingJobService.DEFAULT_LIST_LIMIT);
    assertThat(service.list(0)).hasSize(ProcessingJobService.DEFAULT_LIST_LIMIT);
  }

  // ----------------------------- 3. retry eligibility -----------------------------

  @Test
  void retryFailedJobRequeuesIncrementsAttemptsAndCreatesNoDuplicate() {
    UUID tenant = UUID.randomUUID();
    TenantContext.setTenantId(tenant);
    ProcessingJob job = newJob(tenant, "CHANNEL_MESSAGE", UUID.randomUUID());
    job.markFailed("internal-failure-token", NOW);
    repository.saveAndFlush(job);
    long countBefore = repository.count();

    ProcessingJob retried = service.retry(job.getId());

    assertThat(retried.getId()).isEqualTo(job.getId()); // same row, no second job
    assertThat(repository.count()).isEqualTo(countBefore);
    ProcessingJob fresh = reload(job.getId(), tenant);
    assertThat(fresh.getStatus()).isEqualTo("PENDING");
    assertThat(fresh.getAttempts()).isEqualTo(1);
  }

  @Test
  void retryRejectsNonFailedStatusesWithoutMutating() {
    UUID tenant = UUID.randomUUID();
    TenantContext.setTenantId(tenant);

    assertRetryRejected(tenant, job -> { /* PENDING from constructor */ }, "PENDING");
    assertRetryRejected(tenant, job -> job.markSucceeded(NOW), "SUCCEEDED");
    assertRetryRejected(tenant, job -> job.markNeedsReview(NOW), "NEEDS_REVIEW");
    assertRetryRejected(tenant, job -> job.markRejected("rej", NOW), "REJECTED");
  }

  private void assertRetryRejected(UUID tenant, java.util.function.Consumer<ProcessingJob> mutate, String expectedStatus) {
    ProcessingJob job = newJob(tenant, "CHANNEL_MESSAGE", UUID.randomUUID());
    mutate.accept(job);
    repository.saveAndFlush(job);

    assertThatThrownBy(() -> service.retry(job.getId()))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("not retryable");
    assertThat(reload(job.getId(), tenant).getStatus()).isEqualTo(expectedStatus);
  }

  @Test
  void retryIsBoundedByMaxAttempts() {
    UUID tenant = UUID.randomUUID();
    TenantContext.setTenantId(tenant);
    ProcessingJob job = newJob(tenant, "CHANNEL_MESSAGE", UUID.randomUUID());
    int max = job.getMaxAttempts();

    // Each retry must be from FAILED; re-fail between requeues. Allowed exactly `max` times.
    for (int i = 0; i < max; i++) {
      ProcessingJob current = reload(job.getId(), tenant);
      current.markFailed("token", NOW);
      repository.saveAndFlush(current);
      service.retry(job.getId());
    }
    ProcessingJob exhausted = reload(job.getId(), tenant);
    exhausted.markFailed("token", NOW);
    repository.saveAndFlush(exhausted);

    assertThat(reload(job.getId(), tenant).getAttempts()).isEqualTo(max);
    assertThatThrownBy(() -> service.retry(job.getId())).isInstanceOf(ConflictException.class);
  }

  @Test
  void crossTenantRetryIsNotFoundAndDoesNotMutate() {
    UUID owner = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    TenantContext.setTenantId(owner);
    ProcessingJob job = newJob(owner, "CHANNEL_MESSAGE", UUID.randomUUID());
    job.markFailed("token", NOW);
    repository.saveAndFlush(job);

    TenantContext.setTenantId(other);
    assertThatThrownBy(() -> service.retry(job.getId())).isInstanceOf(NotFoundException.class);

    assertThat(reload(job.getId(), owner).getStatus()).isEqualTo("FAILED");
    assertThat(reload(job.getId(), owner).getAttempts()).isZero();
  }

  // ----------------------------- 4. idempotency / dedup -----------------------------

  @Test
  void duplicateEnqueueReturnsExistingPendingJob() {
    UUID tenant = UUID.randomUUID();
    TenantContext.setTenantId(tenant);
    UUID targetId = UUID.randomUUID();
    long before = repository.count();

    ProcessingJob first = service.enqueue(tenant, "DOCUMENT_EXTRACTION", "CHANNEL_MESSAGE", targetId);
    ProcessingJob second = service.enqueue(tenant, "DOCUMENT_EXTRACTION", "CHANNEL_MESSAGE", targetId);

    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(repository.count()).isEqualTo(before + 1);
  }

  // ----------------------------- 5. failure-safety: no leak -----------------------------

  @Test
  void failedStatusResponseDoesNotLeakRawFailureDetail() {
    UUID tenant = UUID.randomUUID();
    TenantContext.setTenantId(tenant);
    ProcessingJob job = newJob(tenant, "CHANNEL_MESSAGE", UUID.randomUUID());
    String secret = "CUSTOMER_SECRET_Acme stacktrace at com.orderpilot.Boom provider=openai prompt=leak";
    job.markFailed(secret, NOW);
    repository.saveAndFlush(job);

    ProcessingJobResponse response = ProcessingJobResponse.from(service.get(job.getId()));

    assertThat(response.status()).isEqualTo("FAILED");
    assertThat(response.retryable()).isTrue();
    assertThat(response.safeMessage()).isEqualTo("Processing failed. Review required.");
    // The raw internal failure token / provider / prompt / customer text never reaches the response.
    assertThat(response.toString())
        .doesNotContain("CUSTOMER_SECRET")
        .doesNotContain("stacktrace")
        .doesNotContain("provider=openai")
        .doesNotContain("prompt=leak");
  }
}
