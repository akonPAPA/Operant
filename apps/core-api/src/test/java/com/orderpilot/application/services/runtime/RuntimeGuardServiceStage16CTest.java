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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-16C Quota + Rate Limit Enforcement — service-level enforcement tests.
 *
 * <p>Quota uses the Spring-wired OP-CAP-16B {@code UsageMeterService} (so {@code QuotaPolicy} is the
 * real enforcement source). Rate limiting is exercised through a hand-built {@link RateLimitService}
 * with an adjustable {@link Clock} so windowed behavior is fully deterministic. No external Redis is
 * required and no controller is wired.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({UsageMeterService.class, CoreConfiguration.class})
class RuntimeGuardServiceStage16CTest {
  @Autowired private UsageMeterService usageMeterService;
  @Autowired private QuotaPolicyRepository quotaPolicyRepository;

  private MutableClock clock;
  private RuntimeGuardService guard;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(Instant.ofEpochSecond(1000));
    QuotaGuard quotaGuard = new QuotaGuard(usageMeterService);
    RateLimitService rateLimitService = new RateLimitService(new InMemoryRateLimitStore(), clock);
    guard = new RuntimeGuardService(quotaGuard, rateLimitService);
  }

  // ---------------------------------------------------------------------------------------------
  // Quota
  // ---------------------------------------------------------------------------------------------

  // 1. Allows when no quota policy exists.
  @Test
  void quotaAllowsWhenNoPolicy() {
    UUID tenantId = UUID.randomUUID();
    RuntimeGuardDecision decision =
        guard.checkQuota(RuntimeGuardRequest.of(tenantId, RuntimeOperationType.AI_ROUTING_DECISION, 50L));

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.NO_POLICY);
    assertThat(decision.httpStatusHint()).isEqualTo(200);
    assertThat(decision.limit()).isNull();
  }

  // 2. Allows when policy exists and requested units fit remaining quota.
  @Test
  void quotaAllowsWithinLimit() {
    UUID tenantId = UUID.randomUUID();
    seedPolicy(tenantId, 1000L);

    RuntimeGuardDecision decision =
        guard.checkQuota(RuntimeGuardRequest.of(tenantId, RuntimeOperationType.AI_ROUTING_DECISION, 50L));

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.WITHIN_LIMIT);
    assertThat(decision.limit()).isEqualTo(1000L);
    assertThat(decision.remaining()).isEqualTo(1000L);
  }

  // 3. Denies when requested units exceed quota.
  @Test
  void quotaDeniesWhenExceeded() {
    UUID tenantId = UUID.randomUUID();
    seedPolicy(tenantId, 100L);
    recordUsage(tenantId, 90L, "u1");

    RuntimeGuardDecision decision =
        guard.checkQuota(RuntimeGuardRequest.of(tenantId, RuntimeOperationType.AI_ROUTING_DECISION, 50L));

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.QUOTA_LIMIT_EXCEEDED);
    assertThat(decision.httpStatusHint()).isEqualTo(403);
  }

  // 4. Existing usage counter is respected.
  @Test
  void quotaRespectsExistingUsageCounter() {
    UUID tenantId = UUID.randomUUID();
    seedPolicy(tenantId, 100L);
    recordUsage(tenantId, 80L, "u1");

    RuntimeGuardDecision decision =
        guard.checkQuota(RuntimeGuardRequest.of(tenantId, RuntimeOperationType.AI_ROUTING_DECISION, 10L));

    assertThat(decision.used()).isEqualTo(80L);
    assertThat(decision.remaining()).isEqualTo(20L);
    assertThat(decision.allowed()).isTrue();
  }

  // 5. Negative requested units follow 16B normalization convention (clamped to zero).
  @Test
  void negativeRequestedUnitsAreNormalizedToZero() {
    UUID tenantId = UUID.randomUUID();
    seedPolicy(tenantId, 100L);

    RuntimeGuardDecision decision =
        guard.checkQuota(
            new RuntimeGuardRequest(
                tenantId,
                RuntimeOperationType.AI_ROUTING_DECISION,
                UsageMetricType.AI_INPUT_UNITS,
                -500L,
                null,
                null,
                null));

    assertThat(decision.requestedUnits()).isZero();
    assertThat(decision.allowed()).isTrue();
    assertThat(decision.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.WITHIN_LIMIT);
  }

  // 6. Large values do not overflow.
  @Test
  void largeRequestedUnitsDoNotOverflow() {
    UUID tenantId = UUID.randomUUID();
    seedPolicy(tenantId, 100L);

    RuntimeGuardDecision decision =
        guard.checkQuota(
            new RuntimeGuardRequest(
                tenantId,
                RuntimeOperationType.AI_ROUTING_DECISION,
                UsageMetricType.AI_INPUT_UNITS,
                Long.MAX_VALUE,
                null,
                null,
                null));

    assertThat(decision.requestedUnits()).isEqualTo(Long.MAX_VALUE);
    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.QUOTA_LIMIT_EXCEEDED);
  }

  // 7. Decision is tenant-scoped: tenant A usage does not affect tenant B.
  @Test
  void quotaIsTenantScoped() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    seedPolicy(tenantB, 100L);
    recordUsage(tenantA, 200L, "a1");

    RuntimeGuardDecision decisionB =
        guard.checkQuota(RuntimeGuardRequest.of(tenantB, RuntimeOperationType.AI_ROUTING_DECISION, 10L));

    assertThat(decisionB.used()).isZero();
    assertThat(decisionB.allowed()).isTrue();
  }

  // ---------------------------------------------------------------------------------------------
  // Rate limiting
  // ---------------------------------------------------------------------------------------------

  // 8. Rate limit allows within window.
  @Test
  void rateLimitAllowsWithinWindow() {
    UUID tenantId = UUID.randomUUID();
    RateLimitDecision decision =
        guard.checkRateLimit(RuntimeGuardRequest.of(tenantId, RuntimeOperationType.SEARCH_QUERY, 0L));

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.RATE_LIMIT_WITHIN_WINDOW);
    assertThat(decision.weightConsumed()).isEqualTo(1L);
    assertThat(decision.retryAfterSeconds()).isZero();
  }

  // 9. Rate limit denies after weighted window budget is exhausted.
  @Test
  void rateLimitDeniesAfterBudgetExhausted() {
    UUID tenantId = UUID.randomUUID();
    // AI_DOCUMENT_EXTRACTION weight=8, budget=40 → 5 allowed (40), 6th (48) denied.
    RuntimeGuardRequest req =
        RuntimeGuardRequest.of(tenantId, RuntimeOperationType.AI_DOCUMENT_EXTRACTION, 0L);
    for (int i = 0; i < 5; i++) {
      assertThat(guard.checkRateLimit(req).allowed()).as("call %s", i).isTrue();
    }
    RateLimitDecision denied = guard.checkRateLimit(req);

    assertThat(denied.allowed()).isFalse();
    assertThat(denied.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.RATE_LIMIT_EXCEEDED);
    assertThat(denied.windowUsed()).isGreaterThan(denied.windowBudget());
  }

  // 10. Retry-after is deterministic and positive for a denied rate-limit decision.
  @Test
  void retryAfterIsDeterministicAndPositive() {
    UUID tenantId = UUID.randomUUID();
    // BULK_IMPORT weight=10, budget=30 → 3 allowed, 4th denied. Clock fixed at epoch 1000:
    // windowStart=960, window=60 → retryAfter = 960 + 60 - 1000 = 20.
    RuntimeGuardRequest req =
        RuntimeGuardRequest.of(tenantId, RuntimeOperationType.BULK_IMPORT, 0L);
    for (int i = 0; i < 3; i++) {
      guard.checkRateLimit(req);
    }
    RateLimitDecision denied = guard.checkRateLimit(req);

    assertThat(denied.allowed()).isFalse();
    assertThat(denied.retryAfterSeconds()).isEqualTo(20L);
    assertThat(denied.retryAfterSeconds()).isPositive();
  }

  // 11. Rate limit bucket resets after the window advances.
  @Test
  void rateLimitResetsAfterWindowAdvances() {
    UUID tenantId = UUID.randomUUID();
    RuntimeGuardRequest req =
        RuntimeGuardRequest.of(tenantId, RuntimeOperationType.BULK_IMPORT, 0L);
    for (int i = 0; i < 3; i++) {
      guard.checkRateLimit(req);
    }
    assertThat(guard.checkRateLimit(req).allowed()).isFalse();

    // Advance beyond the 60s window → fresh budget.
    clock.advanceSeconds(60L);
    RateLimitDecision afterReset = guard.checkRateLimit(req);

    assertThat(afterReset.allowed()).isTrue();
    assertThat(afterReset.windowUsed()).isEqualTo(10L);
  }

  // 12. Endpoint weights are applied: expensive operation consumes more than cheap operation.
  @Test
  void endpointWeightsAreApplied() {
    UUID tenantId = UUID.randomUUID();
    RateLimitDecision cheap =
        guard.checkRateLimit(RuntimeGuardRequest.of(tenantId, RuntimeOperationType.SEARCH_QUERY, 0L));
    RateLimitDecision expensive =
        guard.checkRateLimit(RuntimeGuardRequest.of(tenantId, RuntimeOperationType.BULK_IMPORT, 0L));

    assertThat(cheap.weightConsumed()).isEqualTo(1L);
    assertThat(expensive.weightConsumed()).isEqualTo(10L);
    assertThat(expensive.weightConsumed()).isGreaterThan(cheap.weightConsumed());
  }

  // ---------------------------------------------------------------------------------------------
  // Combined guard + stable mapping
  // ---------------------------------------------------------------------------------------------

  // 13. Stable reason codes are returned across allow/deny paths.
  @Test
  void stableReasonCodesAreReturned() {
    UUID tenantId = UUID.randomUUID();
    seedPolicy(tenantId, 100L);
    recordUsage(tenantId, 100L, "u1");

    RuntimeGuardDecision combined =
        guard.checkRuntimeGuard(
            RuntimeGuardRequest.of(tenantId, RuntimeOperationType.AI_ROUTING_DECISION, 50L));

    // Quota denies first (used 100 + 50 > 100) — rate budget is not consumed.
    assertThat(combined.allowed()).isFalse();
    assertThat(combined.reasonCode()).isEqualTo(RuntimeGuardReasonCodes.QUOTA_LIMIT_EXCEEDED);
    assertThat(combined.retryAfterSeconds()).isZero();
  }

  // 14. Quota denial maps to a stable 403 (RUNTIME_QUOTA_EXCEEDED) exception.
  @Test
  void quotaDenialMapsToStable403() {
    UUID tenantId = UUID.randomUUID();
    seedPolicy(tenantId, 100L);
    recordUsage(tenantId, 100L, "u1");
    RuntimeGuardRequest req =
        RuntimeGuardRequest.of(tenantId, RuntimeOperationType.AI_ROUTING_DECISION, 50L);

    assertThatThrownBy(() -> guard.enforce(req))
        .isInstanceOf(RuntimeQuotaExceededException.class)
        .satisfies(
            ex -> {
              RuntimeQuotaExceededException e = (RuntimeQuotaExceededException) ex;
              assertThat(e.getHttpStatus()).isEqualTo(403);
              assertThat(e.getErrorCode()).isEqualTo(RuntimeErrorCodes.RUNTIME_QUOTA_EXCEEDED);
            });
  }

  // 15. Rate denial maps to a stable 429 (RUNTIME_RATE_LIMITED) exception with Retry-After.
  @Test
  void rateDenialMapsToStable429WithRetryAfter() {
    UUID tenantId = UUID.randomUUID();
    // No quota policy → quota always allows; BULK_IMPORT exhausts after 3 enforced calls.
    RuntimeGuardRequest req =
        RuntimeGuardRequest.of(tenantId, RuntimeOperationType.BULK_IMPORT, 0L);
    for (int i = 0; i < 3; i++) {
      guard.enforce(req);
    }

    assertThatThrownBy(() -> guard.enforce(req))
        .isInstanceOf(RuntimeRateLimitedException.class)
        .satisfies(
            ex -> {
              RuntimeRateLimitedException e = (RuntimeRateLimitedException) ex;
              assertThat(e.getHttpStatus()).isEqualTo(429);
              assertThat(e.getErrorCode()).isEqualTo(RuntimeErrorCodes.RUNTIME_RATE_LIMITED);
              assertThat(e.getRetryAfterSeconds()).isPositive();
            });
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

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

  /** Adjustable UTC clock so windowed rate-limit behavior is deterministic. */
  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advanceSeconds(long seconds) {
      this.instant = this.instant.plusSeconds(seconds);
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
