package com.orderpilot.application.services.aiwork;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.runtime.FeatureEntitlementGuard;
import com.orderpilot.application.services.runtime.QuotaGuard;
import com.orderpilot.application.services.runtime.RateLimitService;
import com.orderpilot.application.services.runtime.RuntimeFeatureNotAvailableException;
import com.orderpilot.application.services.runtime.RuntimeFeatureType;
import com.orderpilot.application.services.runtime.RuntimeGuardService;
import com.orderpilot.application.services.runtime.RuntimeQuotaExceededException;
import com.orderpilot.application.services.runtime.RuntimeRateLimitedException;
import com.orderpilot.application.services.runtime.UsageMeterService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.aiwork.AiWorkSourceType;
import com.orderpilot.domain.aiwork.AiWorkSuggestionRepository;
import com.orderpilot.domain.aiwork.AiWorkType;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-16G — advisory AI explanation/summary generation guarded by the full runtime guard
 * (entitlement → quota → rate) BEFORE the provider call. A counting provider proves the provider is
 * never invoked and no suggestion row is created on any denial, that the allowed path invokes the
 * provider exactly once, and that denials are tenant-scoped.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AiWorkService.class, AuditEventService.class, CoreConfiguration.class,
  RuntimeGuardService.class, QuotaGuard.class, RateLimitService.class, FeatureEntitlementGuard.class, UsageMeterService.class,
  AiWorkExplanationGuardStage16GTest.CountingProviderConfig.class})
class AiWorkExplanationGuardStage16GTest {
  private static final RuntimeFeatureType FEATURE = RuntimeFeatureType.AI_VALIDATION_EXPLANATION;

  @Autowired private AiWorkService service;
  @Autowired private AiWorkSuggestionRepository repository;
  @Autowired private CountingAiWorkProvider provider;
  @Autowired private TenantRuntimePlanRepository planRepository;
  @Autowired private FeatureEntitlementRepository entitlementRepository;
  @Autowired private QuotaPolicyRepository quotaPolicyRepository;

  @TestConfiguration
  static class CountingProviderConfig {
    @Bean
    CountingAiWorkProvider aiWorkProvider() {
      return new CountingAiWorkProvider();
    }
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
    provider.calls.set(0);
  }

  @Test
  void allowedPathInvokesProviderExactlyOnce() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);

    var created = service.createSuggestion(
        AiWorkType.VALIDATION_EXPLANATION, AiWorkSourceType.OPERATOR_REVIEW, UUID.randomUUID(),
        "Issue 1\nIssue 2\nIssue 3", null, UUID.randomUUID());

    assertThat(created.getId()).isNotNull();
    assertThat(provider.calls.get()).isEqualTo(1);
    assertThat(repository.count()).isEqualTo(1);
  }

  @Test
  void disabledEntitlementDeniesBeforeProvider() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedDisabledEntitlement(tenantId);
    long before = repository.count();

    assertThatThrownBy(() -> service.createSuggestion(
        AiWorkType.VALIDATION_EXPLANATION, AiWorkSourceType.OPERATOR_REVIEW, UUID.randomUUID(),
        "ctx", null, UUID.randomUUID()))
        .isInstanceOf(RuntimeFeatureNotAvailableException.class);

    assertThat(provider.calls.get()).isZero();
    assertThat(repository.count()).isEqualTo(before);
  }

  @Test
  void quotaDenialDeniesBeforeProvider() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    // 60-line context → ceil(60/25)=3 units; quota limit 2 → denial.
    seedQuota(tenantId, 2L);
    long before = repository.count();

    assertThatThrownBy(() -> service.createSuggestion(
        AiWorkType.REQUEST_SUMMARY, AiWorkSourceType.CHANNEL_MESSAGE, UUID.randomUUID(),
        manyLines(60), null, UUID.randomUUID()))
        .isInstanceOf(RuntimeQuotaExceededException.class);

    assertThat(provider.calls.get()).isZero();
    assertThat(repository.count()).isEqualTo(before);
  }

  @Test
  void rateDenialDeniesBeforeProvider() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    // AI_VALIDATION_EXPLANATION weight 4, budget 60 → 15 calls allowed, the 16th denied.
    for (int i = 0; i < 15; i++) {
      service.createSuggestion(
          AiWorkType.VALIDATION_EXPLANATION, AiWorkSourceType.OPERATOR_REVIEW, UUID.randomUUID(),
          "ctx", null, UUID.randomUUID());
    }
    assertThat(provider.calls.get()).isEqualTo(15);

    assertThatThrownBy(() -> service.createSuggestion(
        AiWorkType.VALIDATION_EXPLANATION, AiWorkSourceType.OPERATOR_REVIEW, UUID.randomUUID(),
        "ctx", null, UUID.randomUUID()))
        .isInstanceOf(RuntimeRateLimitedException.class);

    assertThat(provider.calls.get()).isEqualTo(15);
  }

  @Test
  void tenantADenialDoesNotBlockTenantB() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    seedDisabledEntitlement(tenantA);
    assertThatThrownBy(() -> service.createSuggestion(
        AiWorkType.VALIDATION_EXPLANATION, AiWorkSourceType.OPERATOR_REVIEW, UUID.randomUUID(),
        "ctx", null, UUID.randomUUID()))
        .isInstanceOf(RuntimeFeatureNotAvailableException.class);

    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantB);
    var created = service.createSuggestion(
        AiWorkType.VALIDATION_EXPLANATION, AiWorkSourceType.OPERATOR_REVIEW, UUID.randomUUID(),
        "ctx", null, UUID.randomUUID());
    assertThat(created.getId()).isNotNull();
    assertThat(provider.calls.get()).isEqualTo(1);
  }

  private static String manyLines(int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      sb.append("line ").append(i).append('\n');
    }
    return sb.toString();
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

  /** Counts provider invocations; returns a stable advisory result. */
  static final class CountingAiWorkProvider implements AiWorkProvider {
    final AtomicInteger calls = new AtomicInteger();

    @Override
    public String strategyVersion() {
      return "counting-test-v1";
    }

    @Override
    public AiWorkGenerationResult generate(AiWorkGenerationRequest request) {
      calls.incrementAndGet();
      return new AiWorkGenerationResult(
          "advisory text", "{}", "[]", "LOW", new BigDecimal("0.50"), strategyVersion());
    }
  }
}
