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
import com.orderpilot.application.services.runtime.RuntimeFeaturePolicy;
import com.orderpilot.application.services.runtime.RuntimeFeatureType;
import com.orderpilot.application.services.runtime.RuntimeGuardService;
import com.orderpilot.application.services.runtime.RuntimeQuotaExceededException;
import com.orderpilot.application.services.runtime.RuntimeRateLimitedException;
import com.orderpilot.application.services.runtime.UsageMeterService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.ExtractionResultRepository;
import com.orderpilot.domain.extraction.ExtractionRunRepository;
import com.orderpilot.domain.intake.ChannelMessage;
import com.orderpilot.domain.intake.ChannelMessageRepository;
import com.orderpilot.domain.usage.QuotaEnforcementMode;
import com.orderpilot.domain.usage.QuotaPolicy;
import com.orderpilot.domain.usage.QuotaPolicyRepository;
import com.orderpilot.domain.usage.UsageMetricType;
import com.orderpilot.domain.usage.UsagePeriodType;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * OP-CAP-16D Runtime Guard Live Wiring — proves the runtime guard runs at the live extraction
 * boundary ({@code ExtractionPipelineService.runNow}) before any run/job creation or extraction
 * work, and that a denial leaves no {@code ExtractionRun} record.
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
  ExtractionPipelineGuardStage16DTest.GuardTestConfig.class
})
class ExtractionPipelineGuardStage16DTest {
  @Autowired private ExtractionPipelineService pipelineService;
  @Autowired private ChannelMessageRepository messageRepository;
  @Autowired private ExtractionRunRepository runRepository;
  @Autowired private ExtractionResultRepository resultRepository;
  @Autowired private QuotaPolicyRepository quotaPolicyRepository;
  @Autowired private RuntimeFeaturePolicy featurePolicy;

  @TestConfiguration
  static class GuardTestConfig {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    // Distinct bean name (avoids override conflict) + @Primary so it wins injection over the
    // permissive CoreConfiguration policy, which then backs off via @ConditionalOnMissingBean.
    @Bean
    @Primary
    RuntimeFeaturePolicy testRuntimeFeaturePolicy() {
      return new ConfigurableFeaturePolicy();
    }
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  private ConfigurableFeaturePolicy policy() {
    return (ConfigurableFeaturePolicy) featurePolicy;
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

  // Allowed: guard passes, extraction proceeds, run + result created (existing behavior preserved).
  @Test
  void allowedRequestCreatesRun() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelMessage message = newMessage(tenantId, "msg-ok");
    long runsBefore = runRepository.count();

    var run = pipelineService.runNow(runRequest(message));

    assertThat(runRepository.count()).isEqualTo(runsBefore + 1);
    assertThat(resultRepository.findFirstByTenantIdAndExtractionRunId(tenantId, run.getId()))
        .isPresent();
  }

  // Feature unavailable: 403 RuntimeFeatureNotAvailableException, no run created, no extraction work.
  @Test
  void featureUnavailableDeniesAndCreatesNoRun() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    policy().deny(tenantId);
    ChannelMessage message = newMessage(tenantId, "msg-feature");
    long runsBefore = runRepository.count();

    assertThatThrownBy(() -> pipelineService.runNow(runRequest(message)))
        .isInstanceOf(RuntimeFeatureNotAvailableException.class);

    assertThat(runRepository.count()).isEqualTo(runsBefore);
    assertThat(runRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).isEmpty();
  }

  // Quota exceeded: 403 RuntimeQuotaExceededException, no run created.
  @Test
  void quotaExceededDeniesAndCreatesNoRun() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    // limit 0 → any positive requested unit (the path charges 1) exceeds quota.
    quotaPolicyRepository.save(
        new QuotaPolicy(
            tenantId, null, UsageMetricType.AI_INPUT_UNITS, UsagePeriodType.MONTH, 0L,
            QuotaEnforcementMode.MONITOR, Clock.systemUTC().instant()));
    ChannelMessage message = newMessage(tenantId, "msg-quota");
    long runsBefore = runRepository.count();

    assertThatThrownBy(() -> pipelineService.runNow(runRequest(message)))
        .isInstanceOf(RuntimeQuotaExceededException.class);

    assertThat(runRepository.count()).isEqualTo(runsBefore);
  }

  // Rate limited: AI_DOCUMENT_EXTRACTION weight 8, budget 40 → 5 runs succeed, 6th is 429.
  @Test
  void rateLimitedDeniesAfterBudgetAndCreatesNoExtraRun() {
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

  // Tenant isolation: denying tenant A's feature does not block tenant B.
  @Test
  void tenantIsolationOfFeatureDenial() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    policy().deny(tenantA);

    TenantContext.setTenantId(tenantA);
    ChannelMessage messageA = newMessage(tenantA, "msg-a");
    assertThatThrownBy(() -> pipelineService.runNow(runRequest(messageA)))
        .isInstanceOf(RuntimeFeatureNotAvailableException.class);

    TenantContext.setTenantId(tenantB);
    ChannelMessage messageB = newMessage(tenantB, "msg-b");
    var run = pipelineService.runNow(runRequest(messageB));
    assertThat(run.getId()).isNotNull();
    assertThat(runRepository.findByTenantIdOrderByCreatedAtDesc(tenantB)).hasSize(1);
    assertThat(runRepository.findByTenantIdOrderByCreatedAtDesc(tenantA)).isEmpty();
  }

  /** Configurable feature policy: unavailable for explicitly denied tenants. */
  static final class ConfigurableFeaturePolicy implements RuntimeFeaturePolicy {
    private final Set<UUID> deniedTenants = ConcurrentHashMap.newKeySet();

    void deny(UUID tenantId) {
      deniedTenants.add(tenantId);
    }

    @Override
    public boolean isAvailable(UUID tenantId, RuntimeFeatureType feature) {
      return !deniedTenants.contains(tenantId);
    }
  }
}
