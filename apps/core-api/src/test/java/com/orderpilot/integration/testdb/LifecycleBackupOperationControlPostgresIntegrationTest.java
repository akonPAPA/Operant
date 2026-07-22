package com.orderpilot.integration.testdb;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.application.services.control.lifecycle.LifecycleBackupOperationService;
import com.orderpilot.application.services.control.lifecycle.LifecycleControlException;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * P1-E2A - PostgreSQL concurrency + fencing proof for the durable backup-operation control slice.
 *
 * <p>The fast H2 suite ({@code LifecycleBackupOperationServiceTest}) proves the protocol logic, but H2
 * degrades {@code FOR UPDATE SKIP LOCKED} and its @DataJpaTest single transaction cannot exercise genuine
 * cross-connection contention. This test boots the real application context against a real PostgreSQL
 * (Testcontainers), runs Flyway (V67), and drives genuinely concurrent transactions so the DB unique
 * idempotency index and the row-locked atomic lease are actually exercised.
 *
 * <p>It reuses the existing integration-test convention but overrides the datasource to point at the
 * container and enables the lifecycle executor capability, so it is self-contained and needs only Docker.
 * Without a Docker daemon the whole class is SKIPPED (kept out of the default H2 suite).
 */
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
    // Enable the executor capability so backup requests create operations in this proof only.
    registry.add("orderpilot.control.lifecycle.executor.enabled", () -> true);
  }

  private static final int WORKERS = 8;
  private static final String STAFF_FP = "staff-fingerprint-1";
  private static final String EXEC_FP = "executor-fingerprint-1";

  @Autowired private LifecycleBackupOperationService service;
  @Autowired private LifecycleOperationRepository repository;

  @BeforeEach
  void clean() {
    repository.deleteAll();
  }

  @Test
  void concurrentDuplicateIdempotencyRequestsCreateExactlyOneOperation() throws Exception {
    List<Optional<String>> results = runConcurrent(WORKERS, () -> {
      try {
        return Optional.of(service.requestBackup(STAFF_FP, "same-idempotency-key").getPublicId());
      } catch (LifecycleControlException.IdempotencyConflict conflict) {
        return Optional.empty();
      }
    });

    List<String> publicIds = results.stream().flatMap(Optional::stream).distinct().toList();
    assertThat(publicIds).hasSize(1); // exactly one operation, one public id
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
    assertThat(winners.get()).isEqualTo(1); // atomic lease: exactly one executor won
    assertThat(leasedIds).containsExactly(queued.getPublicId());

    LifecycleOperation reloaded = repository.findByPublicId(queued.getPublicId()).orElseThrow();
    assertThat(reloaded.getState()).isEqualTo(LifecycleOperationState.LEASED);
    assertThat(reloaded.getFencingToken()).isEqualTo(1L);
    assertThat(reloaded.getAttempt()).isEqualTo(1);
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
