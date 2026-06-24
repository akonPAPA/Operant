package com.orderpilot.integration.testdb;

import static com.orderpilot.support.TestTenantFixtures.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.domain.journey.FulfillmentSignal;
import com.orderpilot.domain.journey.FulfillmentSignalRepository;
import com.orderpilot.domain.journey.FulfillmentSignalSource;
import com.orderpilot.domain.journey.FulfillmentSignalType;
import com.orderpilot.support.DatabaseIntegrationTestBase;
import com.orderpilot.support.RequiresPostgresIntegration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * OP-CAP-46B — PostgreSQL proof of the V59 DB-level fulfillment-signal idempotency hardening.
 *
 * <p>The fast H2 suite cannot prove this: H2 runs with {@code spring.flyway.enabled=false} and
 * {@code ddl-auto=create-drop}, so V59 (a PostgreSQL partial unique index
 * {@code ux_fulfillment_signal_idempotency ... WHERE source_ref IS NOT NULL}) is never executed there.
 * This test boots the real application context against a real PostgreSQL (Testcontainers), runs Flyway
 * for the production schema (so V59 is actually applied), and proves the constraint:
 *
 * <ul>
 *   <li>a second signal with the same (tenant_id, journey_id, source_type, signal_type, source_ref)
 *       and a non-null source_ref is rejected at the database layer; and
 *   <li>signals with a NULL source_ref are NOT constrained (the partial index intentionally excludes
 *       them — matching the application-level guard, which only dedupes when a stable source_ref is
 *       supplied).
 * </ul>
 *
 * <p>Like the other {@code *PostgresIntegrationTest} classes it does not run under the default H2 unit
 * suite — it is gated on both the Postgres opt-in system property and Docker availability.
 */
@Testcontainers
@RequiresPostgresIntegration
@EnabledIf("dockerAvailable")
@Sql(scripts = {DatabaseIntegrationTestBase.CLEAN, DatabaseIntegrationTestBase.TENANTS})
class FulfillmentSignalIdempotencyPostgresIntegrationTest extends DatabaseIntegrationTestBase {

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
  }

  private static final Instant BASE = Instant.parse("2026-06-16T12:00:00Z");

  @Autowired private FulfillmentSignalRepository signalRepository;

  @Test
  void duplicateSignalWithSameStableSourceRefIsRejectedByPartialUniqueIndex() {
    UUID journeyId = UUID.randomUUID();
    signalRepository.saveAndFlush(signal(journeyId, "wh-ref-1"));

    // Same tenant + journey + source + type + source_ref -> blocked by ux_fulfillment_signal_idempotency.
    assertThatThrownBy(() -> signalRepository.saveAndFlush(signal(journeyId, "wh-ref-1")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void signalsWithNullSourceRefAreNotConstrained() {
    UUID journeyId = UUID.randomUUID();
    // The partial index has WHERE source_ref IS NOT NULL, so two null-source_ref signals coexist.
    signalRepository.saveAndFlush(signal(journeyId, null));
    signalRepository.saveAndFlush(signal(journeyId, null));

    long packed = signalRepository.findByTenantIdAndJourneyIdOrderByReceivedAtAsc(TENANT_A, journeyId).stream()
        .filter(s -> s.getSignalType() == FulfillmentSignalType.PACKED)
        .count();
    assertThat(packed).isEqualTo(2);
  }

  private FulfillmentSignal signal(UUID journeyId, String sourceRef) {
    return new FulfillmentSignal(TENANT_A, journeyId, FulfillmentSignalSource.MANUAL,
        FulfillmentSignalType.PACKED, "Packed", null, sourceRef, null, false, BASE, BASE);
  }
}
