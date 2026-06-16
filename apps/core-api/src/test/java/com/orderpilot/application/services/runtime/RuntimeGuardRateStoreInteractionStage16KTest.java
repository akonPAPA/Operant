package com.orderpilot.application.services.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.domain.usage.QuotaEnforcementMode;
import com.orderpilot.domain.usage.QuotaPolicy;
import com.orderpilot.domain.usage.QuotaPolicyRepository;
import com.orderpilot.domain.usage.UsageMetricType;
import com.orderpilot.domain.usage.UsagePeriodType;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-16K — proves at the {@link RateLimitStore} level that the runtime guard's
 * entitlement → quota → rate ordering does not consume rate on a denial, and that
 * {@code enforceWithoutRate(...)} never touches the store. Uses a counting store wrapping the
 * in-memory implementation, so it holds for any store implementation (including Redis).
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({UsageMeterService.class, CoreConfiguration.class})
class RuntimeGuardRateStoreInteractionStage16KTest {
  private static final RuntimeOperationType OP = RuntimeOperationType.AI_DOCUMENT_EXTRACTION;
  private static final RuntimeFeatureType FEATURE = RuntimeFeatureType.AI_DOCUMENT_EXTRACTION;

  @Autowired private UsageMeterService usageMeterService;
  @Autowired private QuotaPolicyRepository quotaPolicyRepository;

  private CountingRateLimitStore store;
  private ConfigurableFeaturePolicy featurePolicy;
  private RuntimeGuardService guard;

  @BeforeEach
  void setUp() {
    store = new CountingRateLimitStore();
    featurePolicy = new ConfigurableFeaturePolicy();
    Clock clock = new FixedClock(Instant.ofEpochSecond(1000));
    guard = new RuntimeGuardService(
        new QuotaGuard(usageMeterService),
        new RateLimitService(store, clock),
        new FeatureEntitlementGuard(featurePolicy));
  }

  @Test
  void allowedRequestConsumesRate() {
    UUID tenantId = UUID.randomUUID();
    guard.enforce(RuntimeGuardRequest.of(tenantId, OP, 1), FEATURE);
    assertThat(store.calls.get()).isEqualTo(1);
  }

  @Test
  void entitlementDenialDoesNotConsumeRate() {
    UUID tenantId = UUID.randomUUID();
    featurePolicy.deny(tenantId);
    assertThatThrownBy(() -> guard.enforce(RuntimeGuardRequest.of(tenantId, OP, 1), FEATURE))
        .isInstanceOf(RuntimeFeatureNotAvailableException.class);
    assertThat(store.calls.get()).isZero();
  }

  @Test
  void quotaDenialDoesNotConsumeRate() {
    UUID tenantId = UUID.randomUUID();
    quotaPolicyRepository.save(new QuotaPolicy(
        tenantId, null, UsageMetricType.AI_INPUT_UNITS, UsagePeriodType.MONTH, 0L, QuotaEnforcementMode.MONITOR, Instant.now()));
    assertThatThrownBy(() -> guard.enforce(RuntimeGuardRequest.of(tenantId, OP, 1), FEATURE))
        .isInstanceOf(RuntimeQuotaExceededException.class);
    assertThat(store.calls.get()).isZero();
  }

  @Test
  void enforceWithoutRateDoesNotConsumeRate() {
    UUID tenantId = UUID.randomUUID();
    guard.enforceWithoutRate(RuntimeGuardRequest.of(tenantId, OP, 1), FEATURE);
    assertThat(store.calls.get()).isZero();
  }

  private static final class CountingRateLimitStore implements RateLimitStore {
    private final AtomicInteger calls = new AtomicInteger();
    private final InMemoryRateLimitStore delegate = new InMemoryRateLimitStore();

    @Override
    public long addAndGet(String key, long windowStartEpochSeconds, long windowSeconds, long weight) {
      calls.incrementAndGet();
      return delegate.addAndGet(key, windowStartEpochSeconds, windowSeconds, weight);
    }
  }

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

  private static final class FixedClock extends Clock {
    private final Instant instant;

    private FixedClock(Instant instant) {
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
