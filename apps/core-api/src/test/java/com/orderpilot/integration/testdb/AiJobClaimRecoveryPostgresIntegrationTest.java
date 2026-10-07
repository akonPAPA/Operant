package com.orderpilot.integration.testdb;

import static com.orderpilot.support.TestTenantFixtures.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.aibot.application.port.out.AiJobRepositoryPort;
import com.orderpilot.aibot.application.port.out.AiJobRepositoryPort.ClaimedAiJob;
import com.orderpilot.aibot.domain.aijob.AiJobStatus;
import com.orderpilot.support.DatabaseIntegrationTestBase;
import com.orderpilot.support.RequiresPostgresIntegration;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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
 * PR #318 Slice 2 — PostgreSQL proof that the AiJob claim query recovers jobs whose worker died
 * mid-flight (an expired lease on a non-terminal row), while never re-claiming an active lease and
 * never resurrecting a terminal job.
 *
 * <p>The claim path ({@code AiJobRepositoryAdapter.claimNext} → {@code lockNextClaimable}) is a
 * native {@code FOR UPDATE SKIP LOCKED} query with PostgreSQL null/predicate semantics; H2 cannot
 * fully model it, so this proof boots the real application context against a real PostgreSQL
 * (Testcontainers), runs Flyway for the production schema, and seeds AiJob rows in each lease state.
 *
 * <p>Like the sibling {@code *PostgresIntegrationTest} classes it is gated on Docker and on the
 * {@code orderpilot.postgres.integration.enabled} opt-in, so it is SKIPPED (not failed) on lanes
 * without Docker and never runs under the default H2 unit suite.
 */
@Testcontainers
@RequiresPostgresIntegration
@EnabledIf("dockerAvailable")
@Sql(scripts = {DatabaseIntegrationTestBase.CLEAN, DatabaseIntegrationTestBase.TENANTS})
class AiJobClaimRecoveryPostgresIntegrationTest extends DatabaseIntegrationTestBase {

  // Evaluated before any extension callback so the whole class is SKIPPED when no Docker daemon is
  // present, keeping the default suite green on machines/CI lanes without Docker.
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
  }

  private static final String WORKER = "recovery-worker-1";
  private static final Instant NOW = Instant.parse("2026-06-16T12:00:00Z");
  private static final Instant EXPIRED = NOW.minus(Duration.ofHours(1)); // lease long gone
  private static final Instant ACTIVE = NOW.plus(Duration.ofHours(1)); // lease still held

  @Autowired private AiJobRepositoryPort repository;
  @Autowired private JdbcTemplate jdbc;

  private UUID versionId;

  // ----------------------------------------------------------------------------------------------
  // Positive: a LEASED row with an expired lease (null next_attempt_at) is recovered and re-leased.
  // ----------------------------------------------------------------------------------------------
  @Test
  void expiredLeasedJobIsRecovered() {
    seedParent();
    seedJob("aij-leased-exp", "LEASED", "dead-worker", EXPIRED, 5L, 1, null);

    Optional<ClaimedAiJob> claim = repository.claimNext(WORKER, NOW, NOW.plus(Duration.ofMinutes(5)));

    assertThat(claim).isPresent();
    ClaimedAiJob claimed = claim.get();
    assertThat(claimed.job().publicId()).isEqualTo("aij-leased-exp");
    assertThat(claimed.job().status()).isEqualTo(AiJobStatus.LEASED);
    assertThat(claimed.leaseOwner()).isEqualTo(WORKER); // re-leased to the live worker
    assertThat(claimed.fencingToken()).isEqualTo(6L); // fencing advanced: old worker is now stale
    assertThat(claimed.job().attemptCount()).isEqualTo(2); // retry attempt recorded
  }

  // ----------------------------------------------------------------------------------------------
  // Positive: a RUNNING row with an expired lease is likewise recovered (crash after markRunning).
  // ----------------------------------------------------------------------------------------------
  @Test
  void expiredRunningJobIsRecovered() {
    seedParent();
    seedJob("aij-running-exp", "RUNNING", "dead-worker", EXPIRED, 2L, 1, null);

    Optional<ClaimedAiJob> claim = repository.claimNext(WORKER, NOW, NOW.plus(Duration.ofMinutes(5)));

    assertThat(claim).isPresent();
    assertThat(claim.get().job().publicId()).isEqualTo("aij-running-exp");
    assertThat(claim.get().job().status()).isEqualTo(AiJobStatus.LEASED);
    assertThat(claim.get().fencingToken()).isEqualTo(3L);
  }

  // ----------------------------------------------------------------------------------------------
  // Negative: an ACTIVE lease (worker still alive) must never be stolen.
  // ----------------------------------------------------------------------------------------------
  @Test
  void activeLeaseIsNotClaimed() {
    seedParent();
    seedJob("aij-leased-active", "LEASED", "live-worker", ACTIVE, 1L, 1, null);

    Optional<ClaimedAiJob> claim = repository.claimNext(WORKER, NOW, NOW.plus(Duration.ofMinutes(5)));

    assertThat(claim).isEmpty();
  }

  // ----------------------------------------------------------------------------------------------
  // Regression: terminal rows are never resurrected, even if a stale lease timestamp lingers.
  // ----------------------------------------------------------------------------------------------
  @Test
  void terminalRowsAreNeverRecovered() {
    seedParent();
    seedJob("aij-invalid", "INVALID", null, EXPIRED, 3L, 1, null);
    seedJob("aij-failed", "FAILED", null, EXPIRED, 3L, 1, null);
    seedJob("aij-ready", "SUGGESTION_READY", null, EXPIRED, 3L, 1, null);

    Optional<ClaimedAiJob> claim = repository.claimNext(WORKER, NOW, NOW.plus(Duration.ofMinutes(5)));

    assertThat(claim).isEmpty();
  }

  // ----------------------------------------------------------------------------------------------
  // Adjacent regression: the normal REQUESTED path is unaffected by the recovery clause.
  // ----------------------------------------------------------------------------------------------
  @Test
  void plainRequestedJobStillClaimed() {
    seedParent();
    seedJob("aij-requested", "REQUESTED", null, null, 0L, 0, null);

    Optional<ClaimedAiJob> claim = repository.claimNext(WORKER, NOW, NOW.plus(Duration.ofMinutes(5)));

    assertThat(claim).isPresent();
    assertThat(claim.get().job().publicId()).isEqualTo("aij-requested");
    assertThat(claim.get().job().status()).isEqualTo(AiJobStatus.LEASED);
    assertThat(claim.get().fencingToken()).isEqualTo(1L);
  }

  // ----------------------------------------- helpers -----------------------------------------

  private void seedParent() {
    UUID botId = UUID.randomUUID();
    versionId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO aibot_bot_definition (id, public_id, tenant_id, name) VALUES (?, ?, ?, ?)",
        botId,
        "bot-" + shortId(botId),
        TENANT_A,
        "Recovery Bot " + shortId(botId));
    jdbc.update(
        """
        INSERT INTO aibot_bot_definition_version
          (id, public_id, tenant_id, bot_definition_id, version_number, state, schema_version)
        VALUES (?, ?, ?, ?, 1, 'GENERATING', 'op-cap-m18.v1')
        """,
        versionId,
        "ver-" + shortId(versionId),
        TENANT_A,
        botId);
  }

  private void seedJob(
      String publicId,
      String status,
      String leaseOwner,
      Instant leaseUntil,
      long fencingToken,
      int attemptCount,
      Instant nextAttemptAt) {
    jdbc.update(
        """
        INSERT INTO aibot_ai_job
          (public_id, tenant_id, purpose, bot_definition_version_id, status, idempotency_key,
           attempt_count, lease_owner, lease_until, fencing_token, next_attempt_at, created_at)
        VALUES (?, ?, 'BOT_DEFINITION_GENERATION', ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        publicId,
        TENANT_A,
        versionId,
        status,
        "idem-" + publicId,
        attemptCount,
        leaseOwner,
        leaseUntil == null ? null : Timestamp.from(leaseUntil),
        fencingToken,
        nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt),
        Timestamp.from(NOW.minus(Duration.ofMinutes(30))));
  }

  private static String shortId(UUID id) {
    return id.toString().substring(0, 8);
  }
}
