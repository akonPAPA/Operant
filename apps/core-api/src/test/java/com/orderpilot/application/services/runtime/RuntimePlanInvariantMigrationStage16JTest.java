package com.orderpilot.application.services.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * OP-CAP-16J — verifies the V43 migration adds the Postgres partial unique index enforcing at most
 * one open-ended ACTIVE runtime plan per tenant. (File-content assertion, matching the repo's
 * existing migration-file test convention; the H2 test schema does not run Flyway, so the index is a
 * Postgres-only invariant backing the service-level conflict check.)
 */
class RuntimePlanInvariantMigrationStage16JTest {
  @Test
  void migrationAddsOpenEndedActivePlanUniqueIndex() throws Exception {
    String sql = Files.readString(Path.of("src/main/resources/db/migration/V43__runtime_plan_active_invariant.sql"));
    assertThat(sql).contains("CREATE UNIQUE INDEX IF NOT EXISTS uq_tenant_runtime_plan_active_open");
    assertThat(sql).contains("ON tenant_runtime_plan(tenant_id)");
    assertThat(sql).contains("WHERE status = 'ACTIVE' AND effective_until IS NULL");
  }
}
