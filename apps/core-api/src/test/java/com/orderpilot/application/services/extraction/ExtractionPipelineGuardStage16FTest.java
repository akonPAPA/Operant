package com.orderpilot.application.services.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.Stage4Dtos.ExtractionRunRequest;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.application.services.runtime.FeatureEntitlementGuard;
import com.orderpilot.application.services.runtime.QuotaGuard;
import com.orderpilot.application.services.runtime.RateLimitService;
import com.orderpilot.application.services.runtime.RuntimeFeatureNotAvailableException;
import com.orderpilot.application.services.runtime.RuntimeFeatureType;
import com.orderpilot.application.services.runtime.RuntimeGuardService;
import com.orderpilot.application.services.runtime.RuntimeQuotaExceededException;
import com.orderpilot.application.services.runtime.RuntimeRateLimitedException;
import com.orderpilot.application.services.runtime.RuntimeUnitEstimateRequest;
import com.orderpilot.application.services.runtime.RuntimeUnitEstimator;
import com.orderpilot.application.services.runtime.UsageMeterService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.ExtractionRunRepository;
import com.orderpilot.domain.intake.ChannelMessage;
import com.orderpilot.domain.intake.ChannelMessageRepository;
import com.orderpilot.domain.usage.FeatureEntitlement;
import com.orderpilot.domain.usage.FeatureEntitlementRepository;
import com.orderpilot.domain.usage.QuotaEnforcementMode;
import com.orderpilot.domain.usage.QuotaPolicy;
import com.orderpilot.domain.usage.QuotaPolicyRepository;
import com.orderpilot.domain.usage.TenantRuntimePlan;
import com.orderpilot.domain.usage.TenantRuntimePlanCode;
import com.orderpilot.domain.usage.TenantRuntimePlanRepository;
import com.orderpilot.domain.usage.TenantRuntimePlanStatus;
import com.orderpilot.domain.usage.UsageMetricType;
import com.orderpilot.domain.usage.UsagePeriodType;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-16F — extraction live path with estimator-driven requested units. Proves the estimated
 * units flow into the quota check (no longer hardcoded 1), the fallback stays 1 when no metadata is
 * available, and feature/quota/rate denials still create no {@code ExtractionRun}.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  ExtractionPipelineService.class,
  ExtractionRunService.class,
  TextExtractionService.class,
  SemanticExtractionService.class,
  ConfidenceScoringService.class,
  ExtractionOutputSanitizer.class,
  RuleBasedMockSemanticExtractionProvider.class,
  MessageTextExtractionProvider.class,
  MockDocumentTextExtractionProvider.class,
  PromptInjectionGuardService.class,
  AuditEventService.class,
  JsonSupport.class,
  CoreConfiguration.class,
  com.orderpilot.application.services.runtime.RuntimeControlService.class,
  com.orderpilot.application.services.runtime.AiWorkloadClassifier.class,
  RuntimeGuardService.class,
  QuotaGuard.class,
  RateLimitService.class,
  FeatureEntitlementGuard.class,
  UsageMeterService.class,
  ExtractionPipelineGuardStage16FTest.EstimatorTestConfig.class
})
class ExtractionPipelineGuardStage16FTest {
  private static final RuntimeFeatureType FEATURE = RuntimeFeatureType.AI_DOCUMENT_EXTRACTION;

  @Autowired private ExtractionPipelineService pipelineService;
  @Autowired private ChannelMessageRepository messageRepository;
  @Autowired private ExtractionRunRepository runRepository;
  @Autowired private TenantRuntimePlanRepository planRepository;
  @Autowired private FeatureEntitlementRepository entitlementRepository;
  @Autowired private QuotaPolicyRepository quotaPolicyRepository;
  @Autowired private RuntimeUnitEstimator estimator;

  @TestConfiguration
  static class EstimatorTestConfig {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    // @Primary configurable estimator: forced value when set, else the real default (fallback 1).
    @Bean
    @Primary
    RuntimeUnitEstimator testRuntimeUnitEstimator() {
      return new ConfigurableUnitEstimator();
    }
  }

  @BeforeEach
  void resetEstimator() {
    ((ConfigurableUnitEstimator) estimator).clear();
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  // Allowed path (no plan compat default, fallback units 1, no quota) still creates a run.
  @Test
  void allowedPathCreatesRun() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelMessage message = newMessage(tenantId, "msg-ok");

    var run = pipelineService.runNow(runRequest(message));

    assertThat(run.getId()).isNotNull();
    assertThat(runRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).hasSize(1);
  }

  // Fallback remains 1: with the real estimator and a quota limit of 1, the run is allowed (units<=1).
  @Test
  void fallbackUnitsRemainOne() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedQuota(tenantId, 1L);
    ChannelMessage message = newMessage(tenantId, "msg-fallback");

    var run = pipelineService.runNow(runRequest(message));

    assertThat(run.getId()).isNotNull();
  }

  // Estimated units propagate to quota: forcing 5 units against a limit of 4 denies — a hardcoded 1
  // would have been allowed. No ExtractionRun is created.
  @Test
  void estimatedUnitsPropagateToQuotaDenial() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ((ConfigurableUnitEstimator) estimator).force(5);
    seedQuota(tenantId, 4L);
    ChannelMessage message = newMessage(tenantId, "msg-quota");
    long runsBefore = runRepository.count();

    assertThatThrownBy(() -> pipelineService.runNow(runRequest(message)))
        .isInstanceOf(RuntimeQuotaExceededException.class);

    assertThat(runRepository.count()).isEqualTo(runsBefore);
  }

  // Feature denial still creates no run (estimator value is irrelevant — feature is checked first).
  @Test
  void featureDenialCreatesNoRun() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ((ConfigurableUnitEstimator) estimator).force(5);
    seedDisabledEntitlement(tenantId);
    ChannelMessage message = newMessage(tenantId, "msg-feature");
    long runsBefore = runRepository.count();

    assertThatThrownBy(() -> pipelineService.runNow(runRequest(message)))
        .isInstanceOf(RuntimeFeatureNotAvailableException.class);

    assertThat(runRepository.count()).isEqualTo(runsBefore);
  }

  // Rate denial still creates no extra run (AI_DOCUMENT_EXTRACTION weight 8, budget 40 → 6th denied).
  @Test
  void rateDenialCreatesNoExtraRun() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    long runsBefore = runRepository.count();
    for (int i = 0; i < 5; i++) {
      pipelineService.runNow(runRequest(newMessage(tenantId, "msg-rate-" + i)));
    }
    assertThat(runRepository.count()).isEqualTo(runsBefore + 5);

    ChannelMessage sixth = newMessage(tenantId, "msg-rate-6");
    assertThatThrownBy(() -> pipelineService.runNow(runRequest(sixth)))
        .isInstanceOf(RuntimeRateLimitedException.class);

    assertThat(runRepository.count()).isEqualTo(runsBefore + 5);
  }

  private void seedQuota(UUID tenantId, long limit) {
    quotaPolicyRepository.save(
        new QuotaPolicy(
            tenantId, null, UsageMetricType.AI_INPUT_UNITS, UsagePeriodType.MONTH, limit,
            QuotaEnforcementMode.MONITOR, Instant.now()));
  }

  private void seedDisabledEntitlement(UUID tenantId) {
    Instant now = Instant.now();
    Instant from = now.minusSeconds(3600);
    UUID planId =
        planRepository
            .save(new TenantRuntimePlan(
                tenantId, TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, from, null, now))
            .getId();
    entitlementRepository.save(
        new FeatureEntitlement(tenantId, planId, FEATURE.name(), false, null, from, null, now));
  }

  private ChannelMessage newMessage(UUID tenantId, String externalId) {
    return messageRepository.save(
        new ChannelMessage(
            tenantId, "EMAIL", externalId, "thread-1", "buyer@example.test", "Buyer", null,
            "INBOUND", "TEXT", "Customer: Acme\nNeed 10 EA SKU-001 ship to Almaty by 2026-06-01",
            "{}", "QUEUED", Instant.parse("2026-05-24T00:00:00Z")));
  }

  private ExtractionRunRequest runRequest(ChannelMessage message) {
    return new ExtractionRunRequest("CHANNEL_MESSAGE", message.getId(), null, "RULE_BASED");
  }

  /** Estimator returning a forced value when set, otherwise the real default estimate. */
  static final class ConfigurableUnitEstimator implements RuntimeUnitEstimator {
    private final RuntimeUnitEstimator delegate =
        new com.orderpilot.application.services.runtime.DefaultRuntimeUnitEstimator();
    private volatile Integer forced;

    void force(int units) {
      this.forced = units;
    }

    void clear() {
      this.forced = null;
    }

    @Override
    public int estimate(RuntimeUnitEstimateRequest request) {
      return forced != null ? forced : delegate.estimate(request);
    }
  }
}
