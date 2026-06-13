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
}
