package com.orderpilot.application.services.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.domain.usage.QuotaEnforcementMode;
import com.orderpilot.domain.usage.QuotaPolicy;
import com.orderpilot.domain.usage.QuotaPolicyRepository;
import com.orderpilot.domain.usage.UsageCounterRepository;
import com.orderpilot.domain.usage.UsageEvent;
import com.orderpilot.domain.usage.UsageEventRepository;
import com.orderpilot.domain.usage.UsageEventType;
import com.orderpilot.domain.usage.UsageMetricType;
import com.orderpilot.domain.usage.UsagePeriodType;
import com.orderpilot.domain.usage.UsageSource;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.lang.reflect.Field;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-16B Usage Metering Foundation service tests: AI routing decision recording, counter
 * aggregation, idempotent dedupe, overflow safety, advisory quota decisions, tenant isolation, and
 * the privacy guarantee that no raw text is persisted.
 *
 * <p>Pure data/service layer — no AI, no provider, no external dependency is imported.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({UsageMeterService.class, CoreConfiguration.class})
class UsageMeterServiceStage16BTest {
  @Autowired private UsageMeterService service;
  @Autowired private UsageEventRepository usageEventRepository;
  @Autowired private UsageCounterRepository usageCounterRepository;
  @Autowired private QuotaPolicyRepository quotaPolicyRepository;

  private final AiWorkloadClassifier classifier = new AiWorkloadClassifier();

  private AiRoutingDecision sampleDecision() {
    return classifier.classify(
        new AiWorkloadClassificationRequest(
            AiWorkloadType.DOCUMENT_EXTRACTION, "po", 3, 1, false, false));
  }

  // 1. Records a basic AI routing usage event from AiRoutingDecision.
  @Test
  void recordsAiRoutingDecisionAsUsageEvent() {
    UUID tenantId = UUID.randomUUID();
    AiRoutingDecision decision = sampleDecision();

    UsageRecordingResult result =
        service.recordAiRoutingDecision(tenantId, decision, "doc-1", null);

    assertThat(result.eventId()).isNotNull();
    assertThat(result.deduplicated()).isFalse();
    assertThat(result.metricType()).isEqualTo(UsageMetricType.AI_INPUT_UNITS);
    UsageEvent event = usageEventRepository.findById(result.eventId()).orElseThrow();
    assertThat(event.getTenantId()).isEqualTo(tenantId);
    assertThat(event.getEventType()).isEqualTo(UsageEventType.AI_ROUTING_DECISION);
    assertThat(event.getSource()).isEqualTo(UsageSource.AI_ROUTER);
    assertThat(event.getUnits()).isEqualTo((long) decision.estimatedInputUnits());
  }

  // 2. Updates the current-period usage counter.
  @Test
  void updatesPeriodCounter() {
    UUID tenantId = UUID.randomUUID();
    AiRoutingDecision decision = sampleDecision();

    UsageRecordingResult first = service.recordAiRoutingDecision(tenantId, decision, "a", null);
    UsageRecordingResult second = service.recordAiRoutingDecision(tenantId, decision, "b", null);

    long perEvent = decision.estimatedInputUnits();
    assertThat(first.counterUnitsUsed()).isEqualTo(perEvent);
    assertThat(second.counterUnitsUsed()).isEqualTo(perEvent * 2);
    var counter =
        usageCounterRepository
            .findByTenantIdAndMetricTypeAndPeriodKey(
                tenantId, UsageMetricType.AI_INPUT_UNITS, second.periodKey())
            .orElseThrow();
    assertThat(counter.getUnitsUsed()).isEqualTo(perEvent * 2);
  }

  // 3. Duplicate idempotency key does not double-count.
  @Test
  void duplicateIdempotencyKeyDoesNotDoubleCount() {
    UUID tenantId = UUID.randomUUID();
    AiRoutingDecision decision = sampleDecision();

    UsageRecordingResult first = service.recordAiRoutingDecision(tenantId, decision, "doc", "idem-1");
    UsageRecordingResult second =
        service.recordAiRoutingDecision(tenantId, decision, "doc", "idem-1");

    assertThat(second.deduplicated()).isTrue();
    assertThat(second.eventId()).isEqualTo(first.eventId());
    assertThat(second.unitsRecorded()).isZero();
    assertThat(usageEventRepository.countByTenantId(tenantId)).isEqualTo(1);
    assertThat(second.counterUnitsUsed()).isEqualTo((long) decision.estimatedInputUnits());
  }

  // 4. Null optional sourceRef is accepted.
  @Test
  void nullSourceRefIsAccepted() {
    UUID tenantId = UUID.randomUUID();
    UsageRecordingResult result =
        service.recordAiRoutingDecision(tenantId, sampleDecision(), null, null);

    UsageEvent event = usageEventRepository.findById(result.eventId()).orElseThrow();
    assertThat(event.getSourceRef()).isNull();
  }

  // 5. Negative/impossible units are normalized to zero.
  @Test
  void negativeUnitsAreNormalizedToZero() {
    UUID tenantId = UUID.randomUUID();
    UsageRecordingRequest request =
        new UsageRecordingRequest(
            tenantId,
            UsageEventType.GENERIC_METERED,
            UsageMetricType.AI_INPUT_UNITS,
            -500L,
            UsageSource.SYSTEM,
            null,
            null,
            false,
            false,
            null,
            null,
            null,
            UsagePeriodType.MONTH);

    UsageRecordingResult result = service.recordUsage(request);

    assertThat(result.unitsRecorded()).isZero();
    assertThat(result.counterUnitsUsed()).isZero();
  }

  // 6. Large unit values do not overflow.
  @Test
  void largeUnitValuesSaturateAndDoNotOverflow() {
    UUID tenantId = UUID.randomUUID();
    UsageRecordingRequest big =
        new UsageRecordingRequest(
            tenantId,
            UsageEventType.GENERIC_METERED,
            UsageMetricType.AI_INPUT_UNITS,
            Long.MAX_VALUE - 1,
            UsageSource.SYSTEM,
            null,
            null,
            false,
            false,
            null,
            null,
            "k1",
            UsagePeriodType.MONTH);
    service.recordUsage(big);

    UsageRecordingRequest more =
        new UsageRecordingRequest(
            tenantId,
            UsageEventType.GENERIC_METERED,
            UsageMetricType.AI_INPUT_UNITS,
            Long.MAX_VALUE,
            UsageSource.SYSTEM,
            null,
            null,
            false,
            false,
            null,
            null,
            "k2",
            UsagePeriodType.MONTH);
    UsageRecordingResult result = service.recordUsage(more);

    assertThat(result.counterUnitsUsed()).isEqualTo(Long.MAX_VALUE);
    assertThat(result.counterUnitsUsed()).isPositive();
  }

  // 7. Quota check allows when no policy exists.
  @Test
  void quotaAllowsWhenNoPolicy() {
    UUID tenantId = UUID.randomUUID();
    QuotaDecision decision = service.checkQuota(tenantId, UsageMetricType.AI_INPUT_UNITS, 50L);

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.reasonCode()).isEqualTo(UsageReasonCodes.NO_POLICY);
    assertThat(decision.limitUnits()).isNull();
    assertThat(decision.remainingUnits()).isNull();
  }

  // 8. Quota check allows when within policy limit.
  @Test
  void quotaAllowsWithinLimit() {
    UUID tenantId = UUID.randomUUID();
    seedPolicy(tenantId, 1000L);
    service.recordUsage(genericUnits(tenantId, 100L, "u1"));

    QuotaDecision decision = service.checkQuota(tenantId, UsageMetricType.AI_INPUT_UNITS, 50L);

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.reasonCode()).isEqualTo(UsageReasonCodes.WITHIN_LIMIT);
    assertThat(decision.limitUnits()).isEqualTo(1000L);
    assertThat(decision.usedUnits()).isEqualTo(100L);
    assertThat(decision.remainingUnits()).isEqualTo(900L);
  }

  // 9. Quota check denies in returned decision when over policy limit.
  @Test
  void quotaDeniesInDecisionWhenOverLimit() {
    UUID tenantId = UUID.randomUUID();
    seedPolicy(tenantId, 100L);
    service.recordUsage(genericUnits(tenantId, 90L, "u1"));

    QuotaDecision decision = service.checkQuota(tenantId, UsageMetricType.AI_INPUT_UNITS, 50L);

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo(UsageReasonCodes.LIMIT_EXCEEDED);
    assertThat(decision.usedUnits()).isEqualTo(90L);
    assertThat(decision.requestedAdditionalUnits()).isEqualTo(50L);
    assertThat(decision.remainingUnits()).isEqualTo(10L);
  }

  // 10. No raw text is stored in event metadata.
  @Test
  void noRawTextStoredInMetadata() {
    UUID tenantId = UUID.randomUUID();
    String secret = "TOP-SECRET-CUSTOMER-PAYLOAD-9981";
    // The request type carries no free text field; reasonCode/sourceRef are the only string inputs
    // and are safe tokens. Even so, assert the secret never lands in the persisted metadata.
    UsageRecordingRequest request =
        new UsageRecordingRequest(
            tenantId,
            UsageEventType.AI_ROUTING_DECISION,
            UsageMetricType.AI_INPUT_UNITS,
            5L,
            UsageSource.AI_ROUTER,
            AiWorkloadType.DOCUMENT_EXTRACTION.name(),
            ModelTier.MEDIUM.name(),
            true,
            false,
            "MEDIUM_DOCUMENT_ASYNC",
            "doc-42",
            null,
            UsagePeriodType.MONTH);

    UsageRecordingResult result = service.recordUsage(request);

    UsageEvent event = usageEventRepository.findById(result.eventId()).orElseThrow();
    assertThat(event.getMetadataJson()).doesNotContain(secret);
    assertThat(event.getMetadataJson()).contains("workloadType");
    assertThat(event.getMetadataJson()).contains("modelTier");
    assertThat(event.getMetadataJson()).contains("reasonCode");
  }

  // 11. Tenant isolation: tenant A usage does not affect tenant B counter.
  @Test
  void tenantIsolationOfCounters() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    service.recordUsage(genericUnits(tenantA, 200L, "a1"));

    QuotaDecision decisionB = service.checkQuota(tenantB, UsageMetricType.AI_INPUT_UNITS, 0L);
    assertThat(decisionB.usedUnits()).isZero();
    assertThat(usageEventRepository.countByTenantId(tenantB)).isZero();
    assertThat(usageEventRepository.countByTenantId(tenantA)).isEqualTo(1);
  }

  // 12. Model tier/workload type from the 16A decision are persisted.
  @Test
  void modelTierAndWorkloadTypePersisted() {
    UUID tenantId = UUID.randomUUID();
    AiRoutingDecision decision = sampleDecision();

    UsageRecordingResult result = service.recordAiRoutingDecision(tenantId, decision, "d", null);

    UsageEvent event = usageEventRepository.findById(result.eventId()).orElseThrow();
    assertThat(event.getWorkloadType()).isEqualTo(decision.workloadType().name());
    assertThat(event.getModelTier()).isEqualTo(decision.selectedTier().name());
  }

  // 13. Service has no AI/provider/external dependency (only repos + clock injected).
  @Test
  void serviceHasNoAiOrExternalDependency() {
    Field[] fields = UsageMeterService.class.getDeclaredFields();
    for (Field field : fields) {
      String typeName = field.getType().getName();
      assertThat(typeName)
          .as("field %s", field.getName())
          .matches(
              ".*(UsageEventRepository|UsageCounterRepository|QuotaPolicyRepository)$|"
                  + "^java\\.time\\.Clock$");
    }
  }

  // 14. Empty/null idempotency key still records separate events when no key is provided.
  @Test
  void noIdempotencyKeyRecordsSeparateEvents() {
    UUID tenantId = UUID.randomUUID();
    service.recordUsage(genericUnits(tenantId, 10L, null));
    service.recordUsage(genericUnits(tenantId, 10L, "   "));

    assertThat(usageEventRepository.countByTenantId(tenantId)).isEqualTo(2);
  }

  // 15. Period key is deterministic for the same period type.
  @Test
  void periodKeyIsDeterministic() {
    UUID tenantId = UUID.randomUUID();
    UsageRecordingResult first = service.recordUsage(genericUnits(tenantId, 1L, "p1"));
    UsageRecordingResult second = service.recordUsage(genericUnits(tenantId, 1L, "p2"));

    assertThat(first.periodKey()).isEqualTo(second.periodKey());
    assertThat(first.periodKey()).isNotBlank();
  }

  @Test
  void allowedRuntimeControlDecisionRecordsConsumedUsage() {
    UUID tenantId = UUID.randomUUID();
    RuntimeControlRequest request = runtimeRequest(tenantId, 7L);
    RuntimeControlDecision decision =
        runtimeDecision(tenantId, RuntimeControlOutcome.ALLOW_ASYNC, RuntimeGuardReasonCodes.ALLOWED, true, 7);

    UsageRecordingResult result =
        service.recordRuntimeControlDecision(
            request, decision, UsageSource.EXTRACTION_PIPELINE, "DOCUMENT_EXTRACTION:source-1",
            "runtime-accepted-1", true);

    assertThat(result.unitsRecorded()).isEqualTo(7L);
    UsageEvent event = usageEventRepository.findById(result.eventId()).orElseThrow();
    assertThat(event.getEventType()).isEqualTo(UsageEventType.RUNTIME_CONTROL_DECISION);
    assertThat(event.getUnits()).isEqualTo(7L);
    assertThat(event.getMetadataJson()).contains("\"outcome\":\"ALLOW_ASYNC\"");
    assertThat(event.getMetadataJson()).contains("\"consumedCostUnits\":7");
    assertThat(event.getMetadataJson()).contains("\"downstreamInvoked\":true");
  }

  @Test
  void quotaExceededRuntimeControlDecisionRecordsRejectedEvidenceWithoutCounterIncrement() {
    UUID tenantId = UUID.randomUUID();
    RuntimeControlRequest request = runtimeRequest(tenantId, 9L);
    RuntimeControlDecision decision =
        runtimeDecision(
            tenantId, RuntimeControlOutcome.QUOTA_EXCEEDED,
            RuntimeControlReasonCodes.REQUEST_COST_LIMIT_EXCEEDED, false, 9);

    UsageRecordingResult result =
        service.recordRuntimeControlDecisionEvidence(
            request, decision, UsageSource.EXTRACTION_PIPELINE, "DOCUMENT_EXTRACTION:source-2",
            null, false);

    assertThat(result.unitsRecorded()).isZero();
    assertThat(result.counterUnitsUsed()).isZero();
    UsageEvent event = usageEventRepository.findById(result.eventId()).orElseThrow();
    assertThat(event.getUnits()).isZero();
    assertThat(event.getMetadataJson()).contains("\"outcome\":\"QUOTA_EXCEEDED\"");
    assertThat(event.getMetadataJson()).contains("\"rejectedCostUnits\":9");
    assertThat(event.getMetadataJson()).contains("\"downstreamInvoked\":false");
  }

  @Test
  void rateLimitedRuntimeControlDecisionRecordsRejectedEvidence() {
    UUID tenantId = UUID.randomUUID();
    RuntimeControlRequest request = runtimeRequest(tenantId, 3L);
    RuntimeControlDecision decision =
        runtimeDecision(
            tenantId, RuntimeControlOutcome.RATE_LIMITED,
            RuntimeControlReasonCodes.BACKPRESSURE_QUEUE_DEPTH_EXCEEDED, false, 3);

    UsageRecordingResult result =
        service.recordRuntimeControlDecisionEvidence(
            request, decision, UsageSource.EXTRACTION_PIPELINE, "DOCUMENT_EXTRACTION:source-3",
            null, false);

    UsageEvent event = usageEventRepository.findById(result.eventId()).orElseThrow();
    assertThat(result.unitsRecorded()).isZero();
    assertThat(event.getMetadataJson()).contains("\"outcome\":\"RATE_LIMITED\"");
    assertThat(event.getMetadataJson()).contains("\"rejectedCostUnits\":3");
  }

  @Test
  void asyncRoutedRuntimeControlDecisionIsConsumedNotDenied() {
    UUID tenantId = UUID.randomUUID();
    RuntimeControlRequest request = runtimeRequest(tenantId, 5L);
    RuntimeControlDecision decision =
        runtimeDecision(tenantId, RuntimeControlOutcome.ALLOW_ASYNC, RuntimeGuardReasonCodes.ALLOWED, true, 5);

    UsageRecordingResult result =
        service.recordRuntimeControlDecision(
            request, decision, UsageSource.EXTRACTION_PIPELINE, "DOCUMENT_EXTRACTION:source-4",
            "runtime-async-1", true);

    UsageEvent event = usageEventRepository.findById(result.eventId()).orElseThrow();
    assertThat(result.unitsRecorded()).isEqualTo(5L);
    assertThat(event.getMetadataJson()).contains("\"resolvedExecutionMode\":\"ASYNC\"");
    assertThat(event.getMetadataJson()).contains("\"rejectedCostUnits\":0");
  }

  @Test
  void duplicateRuntimeControlMeteringKeyDoesNotDoubleCountConsumedCost() {
    UUID tenantId = UUID.randomUUID();
    RuntimeControlRequest request = runtimeRequest(tenantId, 11L);
    RuntimeControlDecision allowed =
        runtimeDecision(tenantId, RuntimeControlOutcome.ALLOW_ASYNC, RuntimeGuardReasonCodes.ALLOWED, true, 11);
    RuntimeControlDecision deduped =
        runtimeDecision(
            tenantId, RuntimeControlOutcome.DEDUPED,
            RuntimeControlReasonCodes.DEDUP_IDEMPOTENT_HIT, false, 11);

    UsageRecordingResult first =
        service.recordRuntimeControlDecision(
            request, allowed, UsageSource.EXTRACTION_PIPELINE, "DOCUMENT_EXTRACTION:source-5",
            "runtime-dedup-1", true);
    UsageRecordingResult second =
        service.recordRuntimeControlDecision(
            request, deduped, UsageSource.EXTRACTION_PIPELINE, "DOCUMENT_EXTRACTION:source-5",
            "runtime-dedup-1", false);

    assertThat(first.unitsRecorded()).isEqualTo(11L);
    assertThat(second.deduplicated()).isTrue();
    assertThat(second.unitsRecorded()).isZero();
    assertThat(second.counterUnitsUsed()).isEqualTo(11L);
    assertThat(usageEventRepository.countByTenantIdAndMetricType(tenantId, UsageMetricType.AI_INPUT_UNITS))
        .isEqualTo(1);
  }

  // OP-CAP-41C: a runtime-control decision whose tenant does not match the request tenant is
  // rejected before any usage row or counter is written — runtime evidence cannot be metered against
  // the wrong tenant (tenant-scope guard in recordRuntimeControlDecisionInternal).
  @Test
  void runtimeControlDecisionTenantMismatchIsRejectedAndRecordsNoUsage() {
    UUID requestTenant = UUID.randomUUID();
    UUID otherTenant = UUID.randomUUID();
    RuntimeControlRequest request = runtimeRequest(requestTenant, 7L);
    RuntimeControlDecision foreignDecision =
        runtimeDecision(otherTenant, RuntimeControlOutcome.ALLOW_ASYNC, RuntimeGuardReasonCodes.ALLOWED, true, 7);

    assertThatThrownBy(
            () ->
                service.recordRuntimeControlDecision(
                    request, foreignDecision, UsageSource.EXTRACTION_PIPELINE,
                    "DOCUMENT_EXTRACTION:cross-tenant", "runtime-cross-tenant-1", true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenant");

    // No evidence event nor counter is written for either tenant.
    assertThat(usageEventRepository.countByTenantId(requestTenant)).isZero();
    assertThat(usageEventRepository.countByTenantId(otherTenant)).isZero();
    assertThat(service.checkQuota(requestTenant, UsageMetricType.AI_INPUT_UNITS, 0L).usedUnits())
        .isZero();
    assertThat(service.checkQuota(otherTenant, UsageMetricType.AI_INPUT_UNITS, 0L).usedUnits())
        .isZero();
  }

  // OP-CAP-41C: consumed runtime-control usage for tenant A never lands on tenant B's counter, even
  // for the RUNTIME_CONTROL_DECISION metric path (cross-tenant runtime evidence isolation).
  @Test
  void runtimeControlConsumedUsageIsTenantScoped() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    RuntimeControlRequest requestA = runtimeRequest(tenantA, 13L);
    RuntimeControlDecision allowedA =
        runtimeDecision(tenantA, RuntimeControlOutcome.ALLOW_ASYNC, RuntimeGuardReasonCodes.ALLOWED, true, 13);

    UsageRecordingResult result =
        service.recordRuntimeControlDecision(
            requestA, allowedA, UsageSource.EXTRACTION_PIPELINE, "DOCUMENT_EXTRACTION:tenant-a",
            "runtime-tenant-a-1", true);

    assertThat(result.unitsRecorded()).isEqualTo(13L);
    assertThat(usageEventRepository.countByTenantId(tenantA)).isEqualTo(1);
    assertThat(usageEventRepository.countByTenantId(tenantB)).isZero();
    assertThat(service.checkQuota(tenantB, result.metricType(), 0L).usedUnits()).isZero();
    assertThat(service.checkQuota(tenantA, result.metricType(), 0L).usedUnits()).isEqualTo(13L);
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

  private UsageRecordingRequest genericUnits(UUID tenantId, long units, String idempotencyKey) {
    return new UsageRecordingRequest(
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
        idempotencyKey,
        UsagePeriodType.MONTH);
  }

  private RuntimeControlRequest runtimeRequest(UUID tenantId, long requestedUnits) {
    return new RuntimeControlRequest(
        tenantId,
        null,
        RuntimeWorkloadType.AI_EXTRACTION,
        RuntimeExecutionMode.SYNC,
        RuntimeOperationType.AI_DOCUMENT_EXTRACTION,
        RuntimeFeatureType.AI_DOCUMENT_EXTRACTION,
        new AiWorkloadClassificationRequest(AiWorkloadType.DOCUMENT_EXTRACTION, null, 0, 1, false, false),
        requestedUnits,
        null,
        false,
        0);
  }

  private RuntimeControlDecision runtimeDecision(
      UUID tenantId,
      RuntimeControlOutcome outcome,
      String reasonCode,
      boolean asyncRequired,
      int estimatedUnits) {
    return new RuntimeControlDecision(
        outcome,
        reasonCode,
        AiWorkloadType.DOCUMENT_EXTRACTION,
        asyncRequired ? ModelTier.MEDIUM : ModelTier.NONE,
        tenantId,
        null,
        true,
        null,
        false,
        false,
        asyncRequired,
        false,
        estimatedUnits,
        outcome.allowed() ? 200 : 403,
        0L,
        "safe runtime decision");
  }
}
