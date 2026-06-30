package opcap30;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.AiResultIntakeDtos.AiProcessingResultIntakeRequest;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.application.services.WorkerJobLeaseService;
import com.orderpilot.application.services.extraction.AdvisoryValidationHandoffRequested;
import com.orderpilot.application.services.extraction.AiWorkerResultIntakeService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEvent;
import com.orderpilot.domain.audit.AuditEventRepository;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * OP-CAP-30.1 local race proof: stale PROCESSING recovery and AI-worker result intake are both
 * terminal-state writers for {@code processing_job}, so they must serialize on the same row lock.
 */
@SpringBootTest(
    classes = WorkerStaleRecoveryRaceH2Test.StaleRecoveryRaceTestApp.class,
    properties = {
      "spring.main.web-application-type=none",
      "spring.datasource.url=jdbc:h2:mem:opcap30_stale_recovery;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
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
class WorkerStaleRecoveryRaceH2Test {

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
    JsonSupport.class,
    HandoffEventCounter.class
  })
  static class StaleRecoveryRaceTestApp {
    @Bean
    Clock clock() {
      return Clock.fixed(BASE, ZoneOffset.UTC);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }

  static class HandoffEventCounter {
    private final AtomicInteger count = new AtomicInteger();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onHandoff(AdvisoryValidationHandoffRequested ignored) {
      count.incrementAndGet();
    }

    int count() {
      return count.get();
    }

    void reset() {
      count.set(0);
    }
  }

  private static final Instant BASE = Instant.parse("2026-06-16T12:00:00Z");
  private static final UUID TENANT_A = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Autowired private WorkerJobLeaseService leaseService;
  @Autowired private AiWorkerResultIntakeService intakeService;
  @Autowired private ProcessingJobRepository jobRepository;
  @Autowired private ExtractionRunRepository runRepository;
  @Autowired private ExtractionResultRepository resultRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private HandoffEventCounter handoffCounter;

  @AfterEach
  void cleanup() {
    TenantContext.clear();
    handoffCounter.reset();
    resultRepository.deleteAll();
    runRepository.deleteAll();
    auditEventRepository.deleteAll();
    jobRepository.deleteAll();
  }

  @Test
  void staleRecoveryAndResultIntakeRaceProducesOneTerminalState() throws Exception {
    ProcessingJob job = staleProcessingJob();
    AiProcessingResultIntakeRequest request = successResult(job);

    RaceResult race = runRecoveryAndIntakeRace(request);

    ProcessingJob fresh = jobRepository.findByIdAndTenantId(job.getId(), TENANT_A).orElseThrow();
    assertThat(fresh.getStatus()).isIn("SUCCEEDED", "FAILED");
    assertThat(runRepository.count()).isLessThanOrEqualTo(1);
    assertThat(resultRepository.count()).isLessThanOrEqualTo(1);
    assertThat(countAudit("ai_processing_result.intake_succeeded")).isLessThanOrEqualTo(1);
    assertThat(handoffCounter.count()).isLessThanOrEqualTo(1);

    if ("SUCCEEDED".equals(fresh.getStatus())) {
      assertThat(race.intake().ok()).isTrue();
      assertThat(runRepository.count()).isEqualTo(1);
      assertThat(resultRepository.count()).isEqualTo(1);
      assertThat(runRepository.findAll().get(0).getStatus()).isEqualTo("SUCCEEDED");
      assertThat(countAudit("ai_processing_result.intake_succeeded")).isEqualTo(1);
      assertThat(handoffCounter.count()).isEqualTo(1);
      assertThat(race.recovery().recovered()).isZero();
    } else {
      assertThat(race.recovery().ok()).isTrue();
      assertThat(race.recovery().recovered()).isEqualTo(1);
      assertThat(runRepository.count()).isZero();
      assertThat(resultRepository.count()).isZero();
      assertThat(countAudit("ai_processing_result.intake_succeeded")).isZero();
      assertThat(handoffCounter.count()).isZero();
      assertThat(race.intake().ok()).isFalse();
      assertThat(race.intake().error()).contains("job_not_in_processable_state");
    }
  }

  private RaceResult runRecoveryAndIntakeRace(AiProcessingResultIntakeRequest request) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch start = new CountDownLatch(1);
      Future<RecoveryOutcome> recovery = pool.submit(recovery(ready, start));
      Future<IntakeOutcome> intake = pool.submit(intake(request, ready, start));

      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      return new RaceResult(
          recovery.get(30, TimeUnit.SECONDS),
          intake.get(30, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private Callable<RecoveryOutcome> recovery(CountDownLatch ready, CountDownLatch start) {
    return () -> {
      ready.countDown();
      start.await(10, TimeUnit.SECONDS);
      try {
        return new RecoveryOutcome(
            leaseService.recoverSystemWideStaleProcessing(BASE.minusSeconds(900), 10), null);
      } catch (RuntimeException ex) {
        return new RecoveryOutcome(0, ex.getMessage());
      }
    };
  }

  private Callable<IntakeOutcome> intake(
      AiProcessingResultIntakeRequest request, CountDownLatch ready, CountDownLatch start) {
    return () -> {
      ready.countDown();
      start.await(10, TimeUnit.SECONDS);
      TenantContext.setTenantId(TENANT_A);
      try {
        return new IntakeOutcome(false, intakeService.intake(request).duplicate(), null);
      } catch (RuntimeException ex) {
        return new IntakeOutcome(false, false, ex.getMessage());
      } finally {
        TenantContext.clear();
      }
    };
  }

  private ProcessingJob staleProcessingJob() {
    ProcessingJob job = new ProcessingJob(
        TENANT_A, "DOCUMENT_EXTRACTION", "CHANNEL_MESSAGE", UUID.randomUUID(), 100, BASE);
    job.markProcessing(BASE.minusSeconds(3_600));
    return jobRepository.saveAndFlush(job);
  }

  private long countAudit(String action) {
    return auditEventRepository.findByTenantIdOrderByOccurredAtDesc(TENANT_A).stream()
        .map(AuditEvent::getAction).filter(action::equals).count();
  }

  private AiProcessingResultIntakeRequest successResult(ProcessingJob job) {
    return new AiProcessingResultIntakeRequest(
        job.getId(), job.getTenantId().toString(), job.getTargetType(), job.getTargetId(), "SUCCEEDED",
        Map.of("detected_intent", "RFQ", "document_type", "message", "overall_confidence", 0.82, "advisory_only", true),
        List.of(), List.of(), List.of(),
        Map.of("provider_name", "rule-based-understanding", "mode", "RULE_BASED"),
        "op-cap-07c.v1", BASE, BASE.plusMillis(10), 10L, null);
  }

  private record RaceResult(RecoveryOutcome recovery, IntakeOutcome intake) {}

  private record RecoveryOutcome(int recovered, String error) {
    boolean ok() {
      return error == null;
    }
  }

  private record IntakeOutcome(boolean winner, boolean duplicate, String error) {
    boolean ok() {
      return error == null;
    }
  }
}
