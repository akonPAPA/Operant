package com.orderpilot.application.services.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.domain.usage.FeatureEntitlement;
import com.orderpilot.domain.usage.FeatureEntitlementRepository;
import com.orderpilot.domain.usage.QuotaEnforcementMode;
import com.orderpilot.domain.usage.QuotaPolicy;
import com.orderpilot.domain.usage.QuotaPolicyRepository;
import com.orderpilot.domain.usage.TenantRuntimePlan;
import com.orderpilot.domain.usage.TenantRuntimePlanCode;
import com.orderpilot.domain.usage.TenantRuntimePlanRepository;
import com.orderpilot.domain.usage.TenantRuntimePlanStatus;
import com.orderpilot.domain.usage.UsageEventType;
import com.orderpilot.domain.usage.UsageMetricType;
import com.orderpilot.domain.usage.UsagePeriodType;
import com.orderpilot.domain.usage.UsageSource;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-16E — RuntimeGuardService integration with the persistent entitlement policy. Proves
 * ordering (entitlement → quota → rate) is preserved when entitlement is database-backed: an
 * entitlement denial short-circuits before quota/rate; quota/rate denials still map as in 16C/16D;
 * the no-feature API stays backward compatible.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({UsageMeterService.class, CoreConfiguration.class})
class RuntimeGuardServiceStage16ETest {
  private static final RuntimeOperationType OP = RuntimeOperationType.AI_DOCUMENT_EXTRACTION;
  private static final RuntimeFeatureType FEATURE = RuntimeFeatureType.AI_DOCUMENT_EXTRACTION;
  private static final Instant NOW = Instant.parse("2026-06-13T12:00:00Z");
  private static final Instant PAST = NOW.minusSeconds(3600);

  @Autowired private UsageMeterService usageMeterService;
  @Autowired private TenantRuntimePlanRepository planRepository;
  @Autowired private FeatureEntitlementRepository entitlementRepository;
  @Autowired private QuotaPolicyRepository quotaPolicyRepository;

  private RuntimeGuardService guard;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    PersistentRuntimeFeaturePolicy policy =
        new PersistentRuntimeFeaturePolicy(planRepository, entitlementRepository, clock);
    FeatureEntitlementGuard featureGuard = new FeatureEntitlementGuard(policy);
    QuotaGuard quotaGuard = new QuotaGuard(usageMeterService);
    RateLimitService rateLimitService = new RateLimitService(new InMemoryRateLimitStore(), clock);
    guard = new RuntimeGuardService(quotaGuard, rateLimitService, featureGuard);
  }

  // Denied entitlement (active plan, disabled feature) throws RuntimeFeatureNotAvailableException.
  @Test
  void deniedEntitlementThrowsFeatureException() {
    UUID tenantId = UUID.randomUUID();
    seedPlanFeature(tenantId, TenantRuntimePlanStatus.ACTIVE, false);

    assertThatThrownBy(() -> guard.enforce(req(tenantId), FEATURE))
        .isInstanceOf(RuntimeFeatureNotAvailableException.class)
        .satisfies(
            ex -> {
              RuntimeFeatureNotAvailableException e = (RuntimeFeatureNotAvailableException) ex;
              assertThat(e.getHttpStatus()).isEqualTo(403);
              assertThat(e.getErrorCode())
                  .isEqualTo(RuntimeErrorCodes.RUNTIME_FEATURE_NOT_AVAILABLE);
            });
  }

  // Denied entitlement does not consume quota or rate budget.
  @Test
  void deniedEntitlementDoesNotConsumeQuotaOrRate() {
    UUID tenantId = UUID.randomUUID();
    seedPlanFeature(tenantId, TenantRuntimePlanStatus.ACTIVE, false);

    for (int i = 0; i < 5; i++) {
      assertThat(guard.checkRuntimeGuard(req(tenantId), FEATURE).allowed()).isFalse();
    }

    // Rate window untouched: a probe shows only its own weight.
    RateLimitDecision rate = guard.checkRateLimit(req(tenantId));
    assertThat(rate.windowUsed()).isEqualTo(EndpointWeightPolicy.weightFor(OP));
  }

  // Allowed entitlement (active plan, enabled feature) proceeds to quota/rate and is allowed.
  @Test
  void allowedEntitlementProceedsToQuotaAndRate() {
    UUID tenantId = UUID.randomUUID();
    seedPlanFeature(tenantId, TenantRuntimePlanStatus.ACTIVE, true);

    RuntimeGuardDecision decision = guard.checkRuntimeGuard(req(tenantId), FEATURE);

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.ALLOWED);
  }

  // Quota denial still maps to RuntimeQuotaExceededException (feature enabled, quota exhausted).
  @Test
  void quotaDenialStillMaps() {
    UUID tenantId = UUID.randomUUID();
    seedPlanFeature(tenantId, TenantRuntimePlanStatus.ACTIVE, true);
    quotaPolicyRepository.save(
        new QuotaPolicy(
            tenantId, null, UsageMetricType.AI_INPUT_UNITS, UsagePeriodType.MONTH, 0L,
            QuotaEnforcementMode.MONITOR, NOW));

    assertThatThrownBy(() -> guard.enforce(req(tenantId), FEATURE))
        .isInstanceOf(RuntimeQuotaExceededException.class);
  }

  // Rate denial still maps to RuntimeRateLimitedException (feature enabled, no quota policy).
  @Test
  void rateDenialStillMaps() {
    UUID tenantId = UUID.randomUUID();
    seedPlanFeature(tenantId, TenantRuntimePlanStatus.ACTIVE, true);

    // AI_DOCUMENT_EXTRACTION weight=8, budget=40 → 5 pass, 6th rate-denied.
    for (int i = 0; i < 5; i++) {
      guard.enforce(req(tenantId), FEATURE);
    }
    assertThatThrownBy(() -> guard.enforce(req(tenantId), FEATURE))
        .isInstanceOf(RuntimeRateLimitedException.class);
  }

  // No-feature overload remains backward compatible (skips entitlement gate).
  @Test
  void noFeatureOverloadBackwardCompatible() {
    UUID tenantId = UUID.randomUUID();
    // Even with a disabled entitlement, the no-feature API ignores entitlement entirely.
    seedPlanFeature(tenantId, TenantRuntimePlanStatus.ACTIVE, false);

    RuntimeGuardDecision decision = guard.enforce(req(tenantId));

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.ALLOWED);
  }

  private static RuntimeGuardRequest req(UUID tenantId) {
    return RuntimeGuardRequest.of(tenantId, OP, 1L);
  }

  private void seedPlanFeature(UUID tenantId, TenantRuntimePlanStatus status, boolean enabled) {
    UUID planId =
        planRepository
            .save(new TenantRuntimePlan(tenantId, TenantRuntimePlanCode.PRO, status, PAST, null, NOW))
            .getId();
    entitlementRepository.save(
        new FeatureEntitlement(tenantId, planId, FEATURE.name(), enabled, null, PAST, null, NOW));
  }
}
