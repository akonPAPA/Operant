package com.orderpilot.application.services.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.domain.usage.FeatureEntitlement;
import com.orderpilot.domain.usage.FeatureEntitlementRepository;
import com.orderpilot.domain.usage.TenantRuntimePlan;
import com.orderpilot.domain.usage.TenantRuntimePlanCode;
import com.orderpilot.domain.usage.TenantRuntimePlanRepository;
import com.orderpilot.domain.usage.TenantRuntimePlanStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-16E Persistent Tenant Entitlements — repository-backed tests for {@link
 * PersistentRuntimeFeaturePolicy}: documents the chosen defaults (no plan → compat allow; active
 * plan authoritative; inactive/expired plan denies; effective windows honored) and tenant isolation.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PersistentRuntimeFeaturePolicyStage16ETest {
  private static final RuntimeFeatureType FEATURE = RuntimeFeatureType.AI_DOCUMENT_EXTRACTION;
  private static final Instant NOW = Instant.parse("2026-06-13T12:00:00Z");
  private static final Instant PAST = NOW.minusSeconds(3600);
  private static final Instant FUTURE = NOW.plusSeconds(3600);

  @Autowired private TenantRuntimePlanRepository planRepository;
  @Autowired private FeatureEntitlementRepository entitlementRepository;

  private PersistentRuntimeFeaturePolicy policy;

  @BeforeEach
  void setUp() {
    policy = new PersistentRuntimeFeaturePolicy(
        planRepository, entitlementRepository, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  // tenant without plan → compatibility default allowed
  @Test
  void noPlanUsesCompatibilityDefaultAllowed() {
    FeatureEntitlementDecision decision = policy.evaluate(UUID.randomUUID(), FEATURE);

    assertThat(decision.available()).isTrue();
    assertThat(decision.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.FEATURE_POLICY_COMPAT_DEFAULT);
  }

  // active plan + enabled feature → allowed
  @Test
  void activePlanEnabledFeatureAllows() {
    UUID tenantId = UUID.randomUUID();
    UUID planId = seedPlan(tenantId, TenantRuntimePlanStatus.ACTIVE, PAST, null);
    seedFeature(tenantId, planId, true, PAST, null);

    FeatureEntitlementDecision decision = policy.evaluate(tenantId, FEATURE);

    assertThat(decision.available()).isTrue();
    assertThat(decision.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.FEATURE_AVAILABLE);
  }

  // active plan + disabled feature → denied
  @Test
  void activePlanDisabledFeatureDenies() {
    UUID tenantId = UUID.randomUUID();
    UUID planId = seedPlan(tenantId, TenantRuntimePlanStatus.ACTIVE, PAST, null);
    seedFeature(tenantId, planId, false, PAST, null);

    FeatureEntitlementDecision decision = policy.evaluate(tenantId, FEATURE);

    assertThat(decision.available()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.FEATURE_NOT_AVAILABLE);
    assertThat(decision.httpStatusHint()).isEqualTo(403);
  }

  // suspended plan → denied
  @Test
  void suspendedPlanDenies() {
    UUID tenantId = UUID.randomUUID();
    UUID planId = seedPlan(tenantId, TenantRuntimePlanStatus.SUSPENDED, PAST, null);
    seedFeature(tenantId, planId, true, PAST, null); // even an enabled feature is unreachable

    FeatureEntitlementDecision decision = policy.evaluate(tenantId, FEATURE);

    assertThat(decision.available()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.FEATURE_PLAN_NOT_ACTIVE);
  }

  // expired plan (effective_until in the past) → denied
  @Test
  void expiredPlanWindowDenies() {
    UUID tenantId = UUID.randomUUID();
    UUID planId = seedPlan(tenantId, TenantRuntimePlanStatus.ACTIVE, NOW.minusSeconds(7200), PAST);
    seedFeature(tenantId, planId, true, NOW.minusSeconds(7200), null);

    FeatureEntitlementDecision decision = policy.evaluate(tenantId, FEATURE);

    assertThat(decision.available()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.FEATURE_PLAN_NOT_ACTIVE);
  }

  // tenant A entitlement does not affect tenant B
  @Test
  void tenantIsolation() {
    UUID tenantA = UUID.randomUUID();
    UUID planA = seedPlan(tenantA, TenantRuntimePlanStatus.ACTIVE, PAST, null);
    seedFeature(tenantA, planA, true, PAST, null);

    UUID tenantB = UUID.randomUUID();
    UUID planB = seedPlan(tenantB, TenantRuntimePlanStatus.ACTIVE, PAST, null);
    seedFeature(tenantB, planB, false, PAST, null);

    assertThat(policy.evaluate(tenantA, FEATURE).available()).isTrue();
    assertThat(policy.evaluate(tenantB, FEATURE).available()).isFalse();
  }

  // absent feature under active plan → denied (active plan is authoritative)
  @Test
  void absentFeatureUnderActivePlanDeniesNotEntitled() {
    UUID tenantId = UUID.randomUUID();
    seedPlan(tenantId, TenantRuntimePlanStatus.ACTIVE, PAST, null); // no feature row

    FeatureEntitlementDecision decision = policy.evaluate(tenantId, FEATURE);

    assertThat(decision.available()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.FEATURE_NOT_ENTITLED);
  }

  // feature effective_until in the past → expired denial
  @Test
  void expiredFeatureWindowDeniesExpired() {
    UUID tenantId = UUID.randomUUID();
    UUID planId = seedPlan(tenantId, TenantRuntimePlanStatus.ACTIVE, NOW.minusSeconds(7200), null);
    seedFeature(tenantId, planId, true, NOW.minusSeconds(7200), PAST);

    FeatureEntitlementDecision decision = policy.evaluate(tenantId, FEATURE);

    assertThat(decision.available()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.FEATURE_ENTITLEMENT_EXPIRED);
  }

  // future effective_from → strict: not yet effective → not entitled
  @Test
  void futureFeatureWindowDeniesNotEntitled() {
    UUID tenantId = UUID.randomUUID();
    UUID planId = seedPlan(tenantId, TenantRuntimePlanStatus.ACTIVE, PAST, null);
    seedFeature(tenantId, planId, true, FUTURE, null);

    FeatureEntitlementDecision decision = policy.evaluate(tenantId, FEATURE);

    assertThat(decision.available()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.FEATURE_NOT_ENTITLED);
  }

  private UUID seedPlan(UUID tenantId, TenantRuntimePlanStatus status, Instant from, Instant until) {
    return planRepository
        .save(new TenantRuntimePlan(tenantId, TenantRuntimePlanCode.PRO, status, from, until, NOW))
        .getId();
  }

  private void seedFeature(UUID tenantId, UUID planId, boolean enabled, Instant from, Instant until) {
    entitlementRepository.save(
        new FeatureEntitlement(tenantId, planId, FEATURE.name(), enabled, null, from, until, NOW));
  }
}
