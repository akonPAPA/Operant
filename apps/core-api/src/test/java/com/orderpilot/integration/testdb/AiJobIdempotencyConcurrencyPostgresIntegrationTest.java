package com.orderpilot.integration.testdb;

import static com.orderpilot.support.TestTenantFixtures.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.aibot.application.port.out.AiJobRepositoryPort;
import com.orderpilot.aibot.application.port.out.AiJobRepositoryPort.IdempotentSave;
import com.orderpilot.aibot.domain.aijob.AiJob;
import com.orderpilot.aibot.domain.aijob.AiJobPurpose;
import com.orderpilot.support.DatabaseIntegrationTestBase;
import com.orderpilot.support.RequiresPostgresIntegration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PR #318 Slice 6 — PostgreSQL proof that the idempotent AiJob insert is concurrency-safe.
 *
 * <p>Six workers race to create a job under the SAME (tenant, purpose, idempotency_key). The
 * {@code INSERT ... ON CONFLICT DO NOTHING} upsert must let exactly one win, no worker may see a
 * unique-constraint error, every worker must read back the SAME winning row, and exactly one row may
 * exist. H2 cannot model this (it degrades the concurrency and runs a single shared connection), so
 * this boots a real PostgreSQL via Testcontainers and drives genuinely concurrent transactions.
 *
 * <p>Docker- and opt-in-gated like the sibling {@code *PostgresIntegrationTest} classes: SKIPPED (not
 * failed) where Docker is unavailable, and never part of the default H2 unit suite.
 */
@Testcontainers
@RequiresPostgresIntegration
@EnabledIf("dockerAvailable")
@Sql(scripts = {DatabaseIntegrationTestBase.CLEAN, DatabaseIntegrationTestBase.TENANTS})
class AiJobIdempotencyConcurrencyPostgresIntegrationTest extends DatabaseIntegrationTestBase {

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
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> 12);
  }

  private static final int WORKERS = 6;
  private static final Instant NOW = Instant.parse("2026-06-16T12:00:00Z");
  private static final String IDEM_KEY = "preview:bot_x:v1:deadbeefdeadbeef";
  private static final String FINGERPRINT = "f".repeat(64);

  @Autowired private AiJobRepositoryPort repository;
  @Autowired private JdbcTemplate jdbc;

  private UUID versionId;

  @Test
  void concurrentSameKeyInsertsProduceExactlyOneRowAndOneWinner() throws Exception {
    seedParent();

    List<IdempotentSave> results = runConcurrentSaves();

    long inserted = results.stream().filter(IdempotentSave::inserted).count();
    long recovered = results.stream().filter(r -> !r.inserted()).count();
    List<String> winnerIds = results.stream().map(r -> r.job().publicId()).distinct().toList();

    assertThat(inserted).as("exactly one worker created the row").isEqualTo(1);
    assertThat(recovered).as("the rest recovered the winner without error").isEqualTo(WORKERS - 1);
    assertThat(winnerIds).as("every worker returned the same winning row").hasSize(1);
    assertThat(results).allSatisfy(r -> assertThat(r.job().requestFingerprint()).isEqualTo(FINGERPRINT));
    Long rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM aibot_ai_job WHERE tenant_id = ? AND idempotency_key = ?",
            Long.class,
            TENANT_A,
            IDEM_KEY);
    assertThat(rows).as("exactly one durable row").isEqualTo(1L);
  }

  private List<IdempotentSave> runConcurrentSaves() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
    try {
      CountDownLatch ready = new CountDownLatch(WORKERS);
      CountDownLatch start = new CountDownLatch(1);
      List<Future<IdempotentSave>> futures = new ArrayList<>();
      for (int i = 0; i < WORKERS; i++) {
        Callable<IdempotentSave> worker =
            () -> {
              AiJob job = newJobSharingIdempotencyKey();
              ready.countDown();
              start.await(10, TimeUnit.SECONDS);
              return repository.saveNewIdempotent(job);
            };
        futures.add(pool.submit(worker));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown(); // release all workers simultaneously
      List<IdempotentSave> results = new ArrayList<>();
      for (Future<IdempotentSave> future : futures) {
        results.add(future.get(20, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  /** Distinct public id per worker, but the SAME (tenant, purpose, idempotency key). */
  private AiJob newJobSharingIdempotencyKey() {
    AiJob job =
        new AiJob(
            "aijob_" + UUID.randomUUID().toString().substring(0, 8),
            TENANT_A,
            AiJobPurpose.BOT_INTENT_CLASSIFICATION,
            versionId,
            IDEM_KEY,
            "op-cap-m18.v1",
            NOW);
    job.attachRequestEnvelope("{\"pendingInput\":\"Order status?\"}", FINGERPRINT, "SYNTHETIC");
    return job;
  }

  private void seedParent() {
    UUID botId = UUID.randomUUID();
    versionId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO aibot_bot_definition (id, public_id, tenant_id, name) VALUES (?, ?, ?, ?)",
        botId,
        "bot-" + botId.toString().substring(0, 8),
        TENANT_A,
        "Idem Bot " + botId.toString().substring(0, 8));
    jdbc.update(
        """
        INSERT INTO aibot_bot_definition_version
          (id, public_id, tenant_id, bot_definition_id, version_number, state, schema_version)
        VALUES (?, ?, ?, ?, 1, 'VALIDATED', 'op-cap-m18.v1')
        """,
        versionId,
        "ver-" + versionId.toString().substring(0, 8),
        TENANT_A,
        botId);
  }
}
