package opcap30;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.AiResultIntakeDtos.AiProcessingResultIntakeRequest;
import com.orderpilot.api.dto.AiResultIntakeDtos.AiProcessingResultIntakeResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.application.services.WorkerJobLeaseService;
import com.orderpilot.application.services.extraction.AiWorkerResultIntakeService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEvent;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.extraction.ExtractionRunRepository;
import com.orderpilot.domain.extraction.ExtractionResultRepository;
import com.orderpilot.domain.intake.ProcessingJob;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * OP-CAP-30 — local (Docker-free) concurrency proof for the worker result-drain idempotency lock.
 *
 * <p>The canonical proof runs on real PostgreSQL/Testcontainers
 * ({@code WorkerResultDrainConcurrencyPostgresIntegrationTest}); this companion proves the same fix in
 * process against H2 so it runs in any lane (including developer machines without a Docker daemon).
 *
 * <p>Unlike the {@code @DataJpaTest} lifecycle suite — which shares a single connection/transaction and
 * therefore cannot exercise concurrency — this boots a minimal Spring context with a real Hikari
 * connection <em>pool</em> over a shared named in-memory H2 (PostgreSQL compatibility mode), so each
 * concurrent drain runs its own transaction on its own connection. H2 implements {@code SELECT ... FOR
 * UPDATE} as a blocking row lock (it only lacks {@code SKIP LOCKED}), which is exactly the
 * serialize-and-wait behavior the drain relies on — so the lock is genuinely exercised here.
 *
 * <p>This test FAILS against the previous naive read-then-insert intake: without the row lock, racing
 * drains both pass the existence check and both insert a run + transition the job (two runs / duplicated
 * side effects). With the lock it serializes to exactly one terminal commit.
 *
 * <p>Deliberately placed in a non-{@code com.orderpilot} package with a self-contained nested
 * {@code @SpringBootConfiguration} referenced via {@code classes=}, so it is invisible to the main
 * application's component scan and to {@code @SpringBootConfiguration} discovery of other tests.
 */
@SpringBootTest(
    classes = WorkerResultDrainConcurrencyH2Test.IntakeConcurrencyTestApp.class,
    properties = {
      "spring.main.web-application-type=none",
      // Shared, named in-memory DB kept alive for the pool (DB_CLOSE_DELAY) with a generous lock timeout
      // so a brief serialize-behind-the-winner wait never trips H2's default 1s lock timeout.
      "spring.datasource.url=jdbc:h2:mem:opcap30_result_drain;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
          + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000;"
          + "INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.datasource.hikari.maximum-pool-size=16",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.open-in-view=false",
      "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
      "spring.flyway.enabled=false"
    })
class WorkerResultDrainConcurrencyH2Test {

  @SpringBootConfiguration
  @ImportAutoConfiguration({
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    HibernateJpaAutoConfiguration.class,
    TransactionAutoConfiguration.class
  })
  @EntityScan(basePackages = "com.orderpilot.domain")
  @EnableJpaRepositories(basePackages = "com.orderpilot.domain")
  @Import({
    WorkerJobLeaseService.class,
    AiWorkerResultIntakeService.class,
    AuditEventService.class,
    JsonSupport.class
  })
  static class IntakeConcurrencyTestApp {
    @Bean
    Clock clock() {
      return Clock.fixed(BASE, ZoneOffset.UTC);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }

  private static final int DRAINERS = 6;
  private static final Instant BASE = Instant.parse("2026-06-16T12:00:00Z");
  private static final UUID TENANT_A = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Autowired private WorkerJobLeaseService leaseService;
  @Autowired private AiWorkerResultIntakeService intakeService;
  @Autowired private ProcessingJobRepository jobRepository;
  @Autowired private ExtractionRunRepository runRepository;
  @Autowired private ExtractionResultRepository resultRepository;
  @Autowired private AuditEventRepository auditEventRepository;

  @AfterEach
  void cleanup() {
    TenantContext.clear();
    // Shared DB persists across the pool; reset state between tests so counts are deterministic.
    runRepository.deleteAll();
    resultRepository.deleteAll();
    auditEventRepository.deleteAll();
    jobRepository.deleteAll();
  }

  @Test
  void concurrentDuplicateSuccessDrainAppliesExactlyOnce() throws Exception {
    ProcessingJob job = leaseAsProcessing();
    AiProcessingResultIntakeRequest request = successResult(job);

    List<Outcome> outcomes = runConcurrentDrains(i -> request);

    assertThat(outcomes).hasSize(DRAINERS);
    assertThat(outcomes).allMatch(Outcome::ok);
    assertThat(outcomes.stream().filter(o -> !o.duplicate).count()).isEqualTo(1); // exactly one winner
    assertThat(outcomes.stream().filter(o -> o.duplicate).count()).isEqualTo(DRAINERS - 1L);

    assertThat(jobRepository.findByIdAndTenantId(job.getId(), TENANT_A).orElseThrow().getStatus())
        .isEqualTo("SUCCEEDED");
    assertThat(runRepository.count()).isEqualTo(1); // one advisory run despite the race
    assertThat(resultRepository.count()).isEqualTo(1);
    assertThat(countAudit("ai_processing_result.intake_succeeded")).isEqualTo(1);
    assertThat(countAudit("ai_processing_result.intake_duplicate")).isEqualTo(DRAINERS - 1L);
  }

  @Test
  void concurrentConflictingDrainCommitsExactlyOneTerminalResult() throws Exception {
    ProcessingJob job = leaseAsProcessing();
    AiProcessingResultIntakeRequest success = successResult(job);
    AiProcessingResultIntakeRequest failure = failureResult(job);

    List<Outcome> outcomes = runConcurrentDrains(i -> (i % 2 == 0) ? success : failure);

    assertThat(outcomes).hasSize(DRAINERS);
    assertThat(outcomes).allMatch(o -> o.ok() || o.conflict());
    assertThat(outcomes.stream().filter(o -> o.ok() && !o.duplicate).count()).isEqualTo(1);
    assertThat(outcomes.stream().filter(Outcome::conflict).count()).isGreaterThanOrEqualTo(1);
    assertThat(runRepository.count()).isEqualTo(1);
    String runStatus = runRepository.findAll().get(0).getStatus();
    String jobStatus = jobRepository.findByIdAndTenantId(job.getId(), TENANT_A).orElseThrow().getStatus();
    assertThat(jobStatus).isIn("SUCCEEDED", "FAILED");
    assertThat(jobStatus).isEqualTo("SUCCEEDED".equals(runStatus) ? "SUCCEEDED" : "FAILED");
  }

  // ----------------------------------------- helpers -----------------------------------------

  private record Outcome(boolean duplicate, String error) {
    boolean ok() {
      return error == null;
    }

    boolean conflict() {
      return error != null && error.contains("conflicting_terminal_result");
    }
  }

  private interface RequestFor {
    AiProcessingResultIntakeRequest apply(int index);
  }

  private List<Outcome> runConcurrentDrains(RequestFor requestFor) throws Exception {
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
          TenantContext.setTenantId(TENANT_A);
          try {
            AiProcessingResultIntakeResponse response = intakeService.intake(request);
            return new Outcome(response.duplicate(), null);
          } catch (RuntimeException ex) {
            return new Outcome(false, ex.getMessage());
          } finally {
            TenantContext.clear();
          }
        };
        futures.add(pool.submit(drain));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<Outcome> outcomes = new ArrayList<>();
      for (Future<Outcome> future : futures) {
        outcomes.add(future.get(30, TimeUnit.SECONDS));
      }
      return outcomes;
    } finally {
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private ProcessingJob leaseAsProcessing() {
    ProcessingJob job = jobRepository.saveAndFlush(
        new ProcessingJob(TENANT_A, "DOCUMENT_EXTRACTION", "CHANNEL_MESSAGE", UUID.randomUUID(), 100, BASE));
    TenantContext.setTenantId(TENANT_A);
    try {
      leaseService.claim(1); // PENDING -> PROCESSING
    } finally {
      TenantContext.clear();
    }
    return job;
  }

  private long countAudit(String action) {
    return auditEventRepository.findByTenantIdOrderByOccurredAtDesc(TENANT_A).stream()
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
}
