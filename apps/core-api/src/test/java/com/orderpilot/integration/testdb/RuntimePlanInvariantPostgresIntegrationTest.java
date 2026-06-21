package com.orderpilot.integration.testdb;

import static com.orderpilot.support.DatabaseIntegrationTestBase.CLEAN;
import static com.orderpilot.support.DatabaseIntegrationTestBase.TENANTS;
import static com.orderpilot.support.TestTenantFixtures.TENANT_A;
import static com.orderpilot.support.TestTenantFixtures.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.domain.usage.TenantRuntimePlan;
import com.orderpilot.domain.usage.TenantRuntimePlanCode;
import com.orderpilot.domain.usage.TenantRuntimePlanRepository;
import com.orderpilot.domain.usage.TenantRuntimePlanStatus;
import com.orderpilot.support.DatabaseIntegrationTestBase;
import com.orderpilot.support.RequiresPostgresIntegration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.jdbc.Sql;

/**
 * OP-CAP-16K — proves the V43 partial unique index at the real Postgres level (the H2 test profile
 * disables Flyway, so the index is not exercised there). Requires a running Postgres on the
 * {@code integration-test} profile; run with the existing integration command, e.g.:
 *
 * <pre>mvn -Dtest=RuntimePlanInvariantPostgresIntegrationTest -Dgroups=... test</pre>
 *
 * <p>(It is skipped/errored like the other {@code *PostgresIntegrationTest} classes when no
 * integration database is available — it does not run under the normal H2 suite.)
 */
@Sql(scripts = {CLEAN, TENANTS})
@RequiresPostgresIntegration
class RuntimePlanInvariantPostgresIntegrationTest extends DatabaseIntegrationTestBase {
  private static final Instant NOW = Instant.parse("2026-06-13T12:00:00Z");
  private static final Instant PAST = NOW.minusSeconds(7200);

  @Autowired private TenantRuntimePlanRepository planRepository;

  @Test
  void firstOpenEndedActivePlanSucceeds() {
    TenantRuntimePlan saved = planRepository.saveAndFlush(
        new TenantRuntimePlan(TENANT_A, TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, PAST, null, NOW));
    assertThat(saved.getId()).isNotNull();
  }

  @Test
  void secondOpenEndedActivePlanForSameTenantViolatesDbInvariant() {
    planRepository.saveAndFlush(
        new TenantRuntimePlan(TENANT_A, TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, PAST, null, NOW));

    assertThatThrownBy(() ->
        planRepository.saveAndFlush(
            new TenantRuntimePlan(TENANT_A, TenantRuntimePlanCode.ENTERPRISE, TenantRuntimePlanStatus.ACTIVE, PAST, null, NOW)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void closedHistoricalActivePlanDoesNotViolate() {
    planRepository.saveAndFlush(
        new TenantRuntimePlan(TENANT_A, TenantRuntimePlanCode.PILOT, TenantRuntimePlanStatus.ACTIVE, PAST.minusSeconds(7200), PAST, NOW));
    TenantRuntimePlan open = planRepository.saveAndFlush(
        new TenantRuntimePlan(TENANT_A, TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, PAST, null, NOW));
    assertThat(open.getId()).isNotNull();
  }

  @Test
  void suspendedOpenEndedPlanDoesNotViolate() {
    planRepository.saveAndFlush(
        new TenantRuntimePlan(TENANT_A, TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.SUSPENDED, PAST, null, NOW));
    TenantRuntimePlan active = planRepository.saveAndFlush(
        new TenantRuntimePlan(TENANT_A, TenantRuntimePlanCode.ENTERPRISE, TenantRuntimePlanStatus.ACTIVE, PAST, null, NOW));
    assertThat(active.getId()).isNotNull();
  }

  @Test
  void openEndedActivePlansForDifferentTenantsDoNotViolate() {
    planRepository.saveAndFlush(
        new TenantRuntimePlan(TENANT_A, TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, PAST, null, NOW));
    TenantRuntimePlan other = planRepository.saveAndFlush(
        new TenantRuntimePlan(TENANT_B, TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, PAST, null, NOW));
    assertThat(other.getId()).isNotNull();
  }
}
