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
import com.orderpilot.application.services.runtime.UsageMeterService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.ExtractionRunRepository;
import com.orderpilot.domain.intake.ChannelMessage;
import com.orderpilot.domain.intake.ChannelMessageRepository;
import com.orderpilot.domain.intake.InboundDocument;
import com.orderpilot.domain.intake.InboundDocumentRepository;
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
 * OP-CAP-16G — extraction live path with cheap document metadata threaded into the estimator. Proves
 * that for an INBOUND_DOCUMENT source the already-stored file size drives requested units above 1
 * (so a quota that a hardcoded 1 would have passed now denies), that non-document sources still fall
 * back to 1, and that feature/quota denials still create no {@code ExtractionRun}. Uses the real
 * {@link com.orderpilot.application.services.runtime.DefaultRuntimeUnitEstimator} (no forced value)
 * so the size→units arithmetic is exercised end-to-end.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  ExtractionPipelineService.class,
  com.orderpilot.application.services.ProcessingJobService.class,
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
  ExtractionPipelineGuardStage16GTest.ObjectMapperConfig.class
})
class ExtractionPipelineGuardStage16GTest {
  private static final RuntimeFeatureType FEATURE = RuntimeFeatureType.AI_DOCUMENT_EXTRACTION;
  // 512KB per page-equivalent unit (see DefaultRuntimeUnitEstimator).
  private static final long TWO_MB = 2L * 1024L * 1024L;
  private static final long SMALL = 100L * 1024L;

  @Autowired private ExtractionPipelineService pipelineService;
  @Autowired private InboundDocumentRepository documentRepository;
  @Autowired private ChannelMessageRepository messageRepository;
  @Autowired private ExtractionRunRepository runRepository;
  @Autowired private TenantRuntimePlanRepository planRepository;
  @Autowired private FeatureEntitlementRepository entitlementRepository;
  @Autowired private QuotaPolicyRepository quotaPolicyRepository;

  @TestConfiguration
  static class ObjectMapperConfig {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  // Known file size drives units > 1: a 2MB document → 4 units. A quota limit of 3 denies — a
  // hardcoded 1 (16F document fallback) would have been allowed. No ExtractionRun is created.
  @Test
  void knownFileSizeRaisesUnitsAndQuotaDenies() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedQuota(tenantId, 3L);
    UUID documentId = newDocument(tenantId, TWO_MB);
    long runsBefore = runRepository.count();

    assertThatThrownBy(() -> pipelineService.runNow(documentRequest(documentId)))
        .isInstanceOf(RuntimeQuotaExceededException.class);

    assertThat(runRepository.count()).isEqualTo(runsBefore);
  }

  // Small document → 1 unit; with the same limit of 3 the run is allowed and created.
  @Test
  void smallDocumentStaysWithinQuotaAndCreatesRun() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedQuota(tenantId, 3L);
    UUID documentId = newDocument(tenantId, SMALL);

    var run = pipelineService.runNow(documentRequest(documentId));

    assertThat(run.getId()).isNotNull();
    assertThat(runRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).hasSize(1);
  }

  // No cheap metadata (CHANNEL_MESSAGE source, no stored size) → fallback units 1; allowed at limit 1.
  @Test
  void nonDocumentSourceFallsBackToOneUnit() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedQuota(tenantId, 1L);
    ChannelMessage message =
        messageRepository.save(
            new ChannelMessage(
                tenantId, "EMAIL", "msg-16g", "thread-1", "buyer@example.test", "Buyer", null,
                "INBOUND", "TEXT", "Customer: Acme\nNeed 10 EA SKU-001", "{}", "QUEUED",
                Instant.parse("2026-05-24T00:00:00Z")));

    var run = pipelineService.runNow(new ExtractionRunRequest("CHANNEL_MESSAGE", message.getId(), null, "RULE_BASED"));

    assertThat(run.getId()).isNotNull();
  }

  // A large document whose entitlement is disabled is denied before any run is created (feature is
  // checked first, regardless of the size-derived units).
  @Test
  void featureDenialCreatesNoRunEvenWithLargeDocument() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedDisabledEntitlement(tenantId);
    UUID documentId = newDocument(tenantId, TWO_MB);
    long runsBefore = runRepository.count();

    assertThatThrownBy(() -> pipelineService.runNow(documentRequest(documentId)))
        .isInstanceOf(RuntimeFeatureNotAvailableException.class);

    assertThat(runRepository.count()).isEqualTo(runsBefore);
  }

  // Tenant isolation: tenant B's small-document run is unaffected by tenant A's tight quota denial.
  @Test
  void tenantIsolationOnSizeDerivedUnits() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    seedQuota(tenantA, 3L);
    UUID docA = newDocument(tenantA, TWO_MB);
    assertThatThrownBy(() -> pipelineService.runNow(documentRequest(docA)))
        .isInstanceOf(RuntimeQuotaExceededException.class);

    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantB);
    UUID docB = newDocument(tenantB, TWO_MB);
    var run = pipelineService.runNow(documentRequest(docB));
    assertThat(run.getId()).isNotNull();
    assertThat(runRepository.findByTenantIdOrderByCreatedAtDesc(tenantB)).hasSize(1);
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

  private UUID newDocument(UUID tenantId, long sizeBytes) {
    Instant now = Instant.parse("2026-05-24T00:00:00Z");
    InboundDocument document =
        documentRepository.save(
            new InboundDocument(
                tenantId, "EMAIL", "PURCHASE_ORDER", "QUEUED", "po.pdf", "application/pdf",
                sizeBytes, "obj-key-" + UUID.randomUUID(), "sha-" + UUID.randomUUID(),
                "buyer@example.test", "PO", "{}", now));
    return document.getId();
  }

  private ExtractionRunRequest documentRequest(UUID documentId) {
    return new ExtractionRunRequest("INBOUND_DOCUMENT", documentId, null, "RULE_BASED");
  }
}
