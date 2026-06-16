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
import com.orderpilot.application.services.runtime.UsageMeterService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.ExtractionRunRepository;
import com.orderpilot.domain.intake.ChannelMessage;
import com.orderpilot.domain.intake.ChannelMessageRepository;
import com.orderpilot.domain.usage.FeatureEntitlement;
import com.orderpilot.domain.usage.FeatureEntitlementRepository;
import com.orderpilot.domain.usage.TenantRuntimePlan;
import com.orderpilot.domain.usage.TenantRuntimePlanCode;
import com.orderpilot.domain.usage.TenantRuntimePlanRepository;
import com.orderpilot.domain.usage.TenantRuntimePlanStatus;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.time.Instant;
import java.util.UUID;
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
 * OP-CAP-16E — live extraction path with the real persistent entitlement policy (from
 * CoreConfiguration). Proves that a DB-disabled / inactive-plan entitlement denies extraction before
 * any {@code ExtractionRun} is created, an enabled entitlement proceeds, and tenants without a plan
 * keep the compatibility default.
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
  ExtractionPipelineGuardStage16ETest.JacksonTestConfig.class
})
class ExtractionPipelineGuardStage16ETest {
  private static final RuntimeFeatureType FEATURE = RuntimeFeatureType.AI_DOCUMENT_EXTRACTION;

  @Autowired private ExtractionPipelineService pipelineService;
  @Autowired private ChannelMessageRepository messageRepository;
  @Autowired private ExtractionRunRepository runRepository;
  @Autowired private TenantRuntimePlanRepository planRepository;
  @Autowired private FeatureEntitlementRepository entitlementRepository;

  @TestConfiguration
  static class JacksonTestConfig {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  // Disabled AI_DOCUMENT_EXTRACTION entitlement: 403 denial, no ExtractionRun created.
  @Test
  void disabledEntitlementCreatesNoRun() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedPlanFeature(tenantId, TenantRuntimePlanStatus.ACTIVE, false);
    ChannelMessage message = newMessage(tenantId, "msg-disabled");
    long runsBefore = runRepository.count();

    assertThatThrownBy(() -> pipelineService.runNow(runRequest(message)))
        .isInstanceOf(RuntimeFeatureNotAvailableException.class);

    assertThat(runRepository.count()).isEqualTo(runsBefore);
    assertThat(runRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).isEmpty();
  }

  // Enabled entitlement: extraction proceeds and a run is created.
  @Test
  void enabledEntitlementCreatesRun() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedPlanFeature(tenantId, TenantRuntimePlanStatus.ACTIVE, true);
    ChannelMessage message = newMessage(tenantId, "msg-enabled");

    var run = pipelineService.runNow(runRequest(message));

    assertThat(run.getId()).isNotNull();
    assertThat(runRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).hasSize(1);
  }

  // Suspended plan: feature unreachable, no run created.
  @Test
  void suspendedPlanCreatesNoRun() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedPlanFeature(tenantId, TenantRuntimePlanStatus.SUSPENDED, true);
    ChannelMessage message = newMessage(tenantId, "msg-suspended");
    long runsBefore = runRepository.count();

    assertThatThrownBy(() -> pipelineService.runNow(runRequest(message)))
        .isInstanceOf(RuntimeFeatureNotAvailableException.class);

    assertThat(runRepository.count()).isEqualTo(runsBefore);
  }

  // Tenant isolation: A disabled denies; B without a plan uses the compatibility default and runs.
  @Test
  void tenantIsolationDisabledVsCompatDefault() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    seedPlanFeature(tenantA, TenantRuntimePlanStatus.ACTIVE, false);

    TenantContext.setTenantId(tenantA);
    ChannelMessage messageA = newMessage(tenantA, "msg-a");
    assertThatThrownBy(() -> pipelineService.runNow(runRequest(messageA)))
        .isInstanceOf(RuntimeFeatureNotAvailableException.class);

    TenantContext.setTenantId(tenantB);
    ChannelMessage messageB = newMessage(tenantB, "msg-b");
    var run = pipelineService.runNow(runRequest(messageB));

    assertThat(run.getId()).isNotNull();
    assertThat(runRepository.findByTenantIdOrderByCreatedAtDesc(tenantA)).isEmpty();
    assertThat(runRepository.findByTenantIdOrderByCreatedAtDesc(tenantB)).hasSize(1);
  }

  private void seedPlanFeature(UUID tenantId, TenantRuntimePlanStatus status, boolean enabled) {
    Instant now = Instant.now();
    Instant from = now.minusSeconds(3600);
    UUID planId =
        planRepository
            .save(new TenantRuntimePlan(tenantId, TenantRuntimePlanCode.PRO, status, from, null, now))
            .getId();
    entitlementRepository.save(
        new FeatureEntitlement(tenantId, planId, FEATURE.name(), enabled, null, from, null, now));
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
}
