package com.orderpilot.integration.testdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.application.services.control.lifecycle.LifecycleBackupOperationService;
import com.orderpilot.domain.control.LifecycleOperation;
import com.orderpilot.domain.control.LifecycleOperationRepository;
import com.orderpilot.domain.control.LifecycleOperationState;
import com.orderpilot.support.DatabaseIntegrationTestBase;
import com.orderpilot.support.RequiresPostgresIntegration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL concurrency, fencing, and migration-constraint proof for lifecycle control. */
@Testcontainers
@RequiresPostgresIntegration
@EnabledIf("dockerAvailable")
class LifecycleBackupOperationControlPostgresIntegrationTest extends DatabaseIntegrationTestBase {

  static boolean dockerAvailable() {
    return DockerClientFactory.instance().isDockerAvailable();
  }

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> 16);
    registry.add("orderpilot.control.lifecycle.executor.enabled", () -> true);
  }

  private static final int WORKERS = 8;
  private static final String STAFF_FP = "staff-fingerprint-1";
  private static final String EXEC_FP = "executor-fingerprint-1";

  @Autowired private LifecycleBackupOperationService service;
  @Autowired private LifecycleOperationRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void clean() {
    repository.deleteAll();
  }

  @Test
  void concurrentSamePrincipalIdempotencyRequestsAllReturnOneOperation() throws Exception {
    List<Optional<String>> results = runConcurrent(
        WORKERS,
        () -> Optional.of(service.requestBackup(STAFF_FP, "same-idempotency-key").getPublicId()));

    List<String> returnedIds = results.stream().flatMap(Optional::stream).toList();
    assertThat(returnedIds).hasSize(WORKERS);
    assertThat(returnedIds).containsOnly(returnedIds.get(0));
    assertThat(repository.count()).isEqualTo(1);
  }

  @Test
  void concurrentLeaseOfSingleQueuedOperationHasExactlyOneWinner() throws Exception {
    LifecycleOperation queued = service.requestBackup(STAFF_FP, "idem-lease");

    AtomicInteger winners = new AtomicInteger();
    List<Optional<String>> results = runConcurrent(WORKERS, () -> {
      Optional<LifecycleOperation> leased = service.leaseNext(EXEC_FP);
      if (leased.isPresent()) {
        winners.incrementAndGet();
        return Optional.of(leased.get().getPublicId());
      }
      return Optional.empty();
    });

    List<String> leasedIds = results.stream().flatMap(Optional::stream).toList();
    assertThat(winners.get()).isEqualTo(1);
    assertThat(leasedIds).containsExactly(queued.getPublicId());

    LifecycleOperation reloaded = repository.findByPublicId(queued.getPublicId()).orElseThrow();
    assertThat(reloaded.getState()).isEqualTo(LifecycleOperationState.LEASED);
    assertThat(reloaded.getFencingToken()).isEqualTo(1L);
    assertThat(reloaded.getAttempt()).isEqualTo(1);
  }

  @Test
  void migrationRejectsImpossibleQueuedRowWithTerminalResult() {
    assertThatThrownBy(() -> jdbcTemplate.update("""
        insert into lifecycle_operation (
          public_id, operation_type, state, idempotency_key_hash, requested_by_fingerprint,
          result_code, attempt, fencing_token, lease_expires_at, leased_by_fingerprint,
          created_at, updated_at
        ) values (
          'op_invalid_terminal_queue', 'BACKUP', 'QUEUED', repeat('a', 64), repeat('b', 64),
          'BACKUP_COMPLETED', 0, null, null, null, now(), now()
        )
        """))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(repository.count()).isZero();
  }

  private static List<Optional<String>> runConcurrent(int workers, Callable<Optional<String>> task)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(workers);
    try {
      CountDownLatch ready = new CountDownLatch(workers);
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Optional<String>>> futures = new ArrayList<>();
      for (int i = 0; i < workers; i++) {
        futures.add(pool.submit(() -> {
          ready.countDown();
          start.await(10, TimeUnit.SECONDS);
          return task.call();
        }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<Optional<String>> results = new ArrayList<>();
      for (Future<Optional<String>> future : futures) {
        results.add(future.get(30, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }
}
