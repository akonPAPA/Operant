package com.orderpilot.application.services.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.domain.usage.QuotaEnforcementMode;
import com.orderpilot.domain.usage.QuotaPolicy;
import com.orderpilot.domain.usage.QuotaPolicyRepository;
import com.orderpilot.domain.usage.UsageEventType;
import com.orderpilot.domain.usage.UsageMetricType;
import com.orderpilot.domain.usage.UsagePeriodType;
import com.orderpilot.domain.usage.UsageSource;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-16D Runtime Guard Live Wiring — entitlement → quota → rate orchestration tests.
 *
 * <p>Quota uses the Spring-wired 16B {@code UsageMeterService} (real {@code QuotaPolicy}); rate uses
 * a hand-built {@link RateLimitService} with an adjustable {@link Clock}; the feature gate uses a
 * configurable {@link RuntimeFeaturePolicy}. No Redis, no controller, deterministic.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({UsageMeterService.class, CoreConfiguration.class})
class RuntimeGuardServiceStage16DTest {
  private static final RuntimeOperationType OP = RuntimeOperationType.AI_DOCUMENT_EXTRACTION;
  private static final RuntimeFeatureType FEATURE = RuntimeFeatureType.AI_DOCUMENT_EXTRACTION;

  @Autowired private UsageMeterService usageMeterService;
  @Autowired private QuotaPolicyRepository quotaPolicyRepository;

  private MutableClock clock;
  private ConfigurableFeaturePolicy featurePolicy;
  private RuntimeGuardService guard;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(Instant.ofEpochSecond(1000));
    featurePolicy = new ConfigurableFeaturePolicy();
    QuotaGuard quotaGuard = new QuotaGuard(usageMeterService);
    RateLimitService rateLimitService = new RateLimitService(new InMemoryRateLimitStore(), clock);
    FeatureEntitlementGuard featureGuard = new FeatureEntitlementGuard(featurePolicy);
    guard = new RuntimeGuardService(quotaGuard, rateLimitService, featureGuard);
  }

  // 1. Feature available -> quota checked -> rate checked -> allowed.
  @Test
  void featureAvailableThenQuotaThenRateAllowed() {
    UUID tenantId = UUID.randomUUID();
    RuntimeGuardDecision decision = guard.checkRuntimeGuard(req(tenantId, 1L), FEATURE);

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.ALLOWED);
    assertThat(decision.httpStatusHint()).isEqualTo(200);
  }

  // 2. Feature unavailable -> decision denied with reason FEATURE_NOT_AVAILABLE.
  @Test
  void featureUnavailableReturnsDeniedDecision() {
    UUID tenantId = UUID.randomUUID();
    featurePolicy.deny(tenantId);

    RuntimeGuardDecision decision = guard.checkRuntimeGuard(req(tenantId, 1L), FEATURE);

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.FEATURE_NOT_AVAILABLE);
    assertThat(decision.httpStatusHint()).isEqualTo(403);
  }

  // 3. Feature unavailable via enforce -> throws RuntimeFeatureNotAvailableException.
  @Test
  void featureUnavailableEnforceThrows() {
    UUID tenantId = UUID.randomUUID();
    featurePolicy.deny(tenantId);

    assertThatThrownBy(() -> guard.enforce(req(tenantId, 1L), FEATURE))
        .isInstanceOf(RuntimeFeatureNotAvailableException.class)
        .satisfies(
            ex -> {
              RuntimeFeatureNotAvailableException e = (RuntimeFeatureNotAvailableException) ex;
              assertThat(e.getHttpStatus()).isEqualTo(403);
              assertThat(e.getErrorCode())
                  .isEqualTo(RuntimeErrorCodes.RUNTIME_FEATURE_NOT_AVAILABLE);
            });
  }

  // 4. Feature denial does not consume rate budget.
  @Test
  void featureDenialDoesNotConsumeRateBudget() {
    UUID tenantId = UUID.randomUUID();
    featurePolicy.deny(tenantId);

    // Many feature-denied calls must not touch the rate window.
    for (int i = 0; i < 10; i++) {
      assertThat(guard.checkRuntimeGuard(req(tenantId, 1L), FEATURE).allowed()).isFalse();
    }

    // A direct rate check now shows only this single call's weight (10 denials added nothing).
    RateLimitDecision rate = guard.checkRateLimit(req(tenantId, 1L));
    assertThat(rate.windowUsed()).isEqualTo(EndpointWeightPolicy.weightFor(OP));
  }

  // 5. Feature denial short-circuits before quota: a tenant over quota but lacking the feature
  //    reports FEATURE_NOT_AVAILABLE (not QUOTA_LIMIT_EXCEEDED), proving feature is evaluated first.
  @Test
  void featureDenialShortCircuitsQuota() {
    UUID tenantId = UUID.randomUUID();
    seedPolicy(tenantId, 0L); // any positive request would exceed quota
    featurePolicy.deny(tenantId);

    RuntimeGuardDecision decision = guard.checkRuntimeGuard(req(tenantId, 5L), FEATURE);

    assertThat(decision.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.FEATURE_NOT_AVAILABLE);
  }

  // 6. Quota denial (feature available) still does not consume rate budget.
  @Test
  void quotaDenialDoesNotConsumeRateBudget() {
    UUID tenantId = UUID.randomUUID();
    seedPolicy(tenantId, 100L);
    recordUsage(tenantId, 100L, "u1");

    RuntimeGuardDecision decision = guard.checkRuntimeGuard(req(tenantId, 50L), FEATURE);
    assertThat(decision.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.QUOTA_LIMIT_EXCEEDED);

    RateLimitDecision rate = guard.checkRateLimit(req(tenantId, 1L));
    assertThat(rate.windowUsed()).isEqualTo(EndpointWeightPolicy.weightFor(OP));
  }

  // 7. Rate denial still throws the 429 path (feature available, no quota policy).
  @Test
  void rateDenialThrows429() {
    UUID tenantId = UUID.randomUUID();
    // AI_DOCUMENT_EXTRACTION weight=8, budget=40 → 5 enforced calls pass, 6th rate-denied.
    for (int i = 0; i < 5; i++) {
      guard.enforce(req(tenantId, 1L), FEATURE);
    }

    assertThatThrownBy(() -> guard.enforce(req(tenantId, 1L), FEATURE))
        .isInstanceOf(RuntimeRateLimitedException.class)
        .satisfies(
            ex -> {
              RuntimeRateLimitedException e = (RuntimeRateLimitedException) ex;
              assertThat(e.getHttpStatus()).isEqualTo(429);
              assertThat(e.getErrorCode()).isEqualTo(RuntimeErrorCodes.RUNTIME_RATE_LIMITED);
              assertThat(e.getRetryAfterSeconds()).isPositive();
            });
  }

  // 8. 16C-compatible API (no feature argument) still works.
  @Test
  void noFeatureArgumentPreserves16CBehavior() {
    UUID tenantId = UUID.randomUUID();
    RuntimeGuardDecision decision = guard.enforce(req(tenantId, 1L));

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.ALLOWED);
  }

  // Tenant isolation: denying tenant A's feature does not affect tenant B.
  @Test
  void featureDenialIsTenantScoped() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    featurePolicy.deny(tenantA);

    assertThat(guard.checkRuntimeGuard(req(tenantA, 1L), FEATURE).allowed()).isFalse();
    assertThat(guard.checkRuntimeGuard(req(tenantB, 1L), FEATURE).allowed()).isTrue();
  }

  // ---------------------------------------------------------------------------------------------

  private static RuntimeGuardRequest req(UUID tenantId, long units) {
    return RuntimeGuardRequest.of(tenantId, OP, units);
  }

  private void seedPolicy(UUID tenantId, long limit) {
    quotaPolicyRepository.save(
        new QuotaPolicy(
            tenantId,
            null,
            UsageMetricType.AI_INPUT_UNITS,
            UsagePeriodType.MONTH,
            limit,
            QuotaEnforcementMode.MONITOR,
            Clock.systemUTC().instant()));
  }

  private void recordUsage(UUID tenantId, long units, String key) {
    usageMeterService.recordUsage(
        new UsageRecordingRequest(
            tenantId,
            UsageEventType.GENERIC_METERED,
            UsageMetricType.AI_INPUT_UNITS,
            units,
            UsageSource.SYSTEM,
            null,
            null,
            false,
            false,
            null,
            null,
            key,
            UsagePeriodType.MONTH));
  }

  /** Configurable feature policy: a feature is unavailable for explicitly denied tenants. */
  private static final class ConfigurableFeaturePolicy implements RuntimeFeaturePolicy {
    private final Set<UUID> deniedTenants = ConcurrentHashMap.newKeySet();

    void deny(UUID tenantId) {
      deniedTenants.add(tenantId);
    }

    @Override
    public boolean isAvailable(UUID tenantId, RuntimeFeatureType feature) {
      return !deniedTenants.contains(tenantId);
    }
  }

  /** Adjustable UTC clock for deterministic windowed rate behavior. */
  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    @Override
    public Instant instant() {
      return instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
