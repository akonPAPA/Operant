package com.orderpilot.application.services.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.application.services.trust.OperatorCorrectionLearningService.RecordCorrectionCommand;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.domain.trust.ai.AiMemoryAuthorityLevel;
import com.orderpilot.domain.trust.ai.AiMemoryNamespace;
import com.orderpilot.domain.trust.ai.AiMemoryRecord;
import com.orderpilot.domain.trust.ai.AiMemorySourceType;
import com.orderpilot.domain.trust.events.TrustAiDomainEvent;
import com.orderpilot.domain.trust.events.TrustAiEventStatus;
import com.orderpilot.domain.trust.events.TrustAiEventType;
import com.orderpilot.domain.trust.events.TrustAiProjectionCheckpoint;
import com.orderpilot.domain.trust.events.TrustAiProjectionCheckpointRepository;
import com.orderpilot.domain.trust.events.TrustAiProjectionStatus;
import com.orderpilot.domain.trust.learning.OperatorCorrectionLearningRecord;
import com.orderpilot.domain.trust.learning.OperatorCorrectionStatus;
import com.orderpilot.domain.trust.learning.OperatorCorrectionType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-18 Trust/AI Event Projector Runtime — publishing idempotency, bounded batches, checkpointed
 * idempotent processing, retry → dead-letter, tenant isolation, and safe projector behaviour. Projectors
 * only create advisory AI memory via the OP-CAP-17F governance service; they never mutate business state.
 */
@SpringBootTest
@ActiveProfiles("test")
class TrustAiProjectorRuntimeServiceStage18Test {
  @Autowired private TrustAiEventPublisherService publisher;
  @Autowired private TrustAiProjectorRuntimeService runtime;
  @Autowired private OperatorCorrectionLearningService corrections;
  @Autowired private AiMemoryGovernanceService memory;
  @Autowired private TrustAiProjectionCheckpointRepository checkpoints;
  @Autowired private com.orderpilot.domain.trust.events.TrustAiDomainEventRepository events;

  // ----------------------------- fixtures -----------------------------

  private TrustAiDomainEvent publish(UUID tenantId, TrustAiEventType type, UUID sourceId, String idemKey,
      String summary) {
    return publisher.publishOnce(tenantId, type, AiMemorySourceType.SYSTEM, sourceId, idemKey, summary);
  }

  private OperatorCorrectionLearningRecord approvedCorrection(UUID tenantId, OperatorCorrectionType type,
      String correctedValue, String summary) {
    OperatorCorrectionLearningRecord rec = corrections.recordCorrection(new RecordCorrectionCommand(
        tenantId, type, AiMemorySourceType.OPERATOR_CORRECTION, UUID.randomUUID(), "PRODUCT",
        UUID.randomUUID(), "alias", "old-value", correctedValue, "canonical-alias", summary,
        new BigDecimal("0.90"), null));
    corrections.approveCorrectionForLearning(tenantId, rec.getId(), null);
    return rec;
  }

  private TrustAiProjectionCheckpoint checkpoint(UUID tenantId, UUID eventId) {
    return checkpoints.findByTenantIdAndProjectorNameAndEventId(
        tenantId, AiMemoryEventProjector.PROJECTOR_NAME, eventId).orElseThrow();
  }

  // ----------------------------- 1 & 2. publish + idempotency -----------------------------

  @Test
  void publishRequiresTenantAndIdempotencyKey() {
    assertThatThrownBy(() -> publisher.publishOnce(null, TrustAiEventType.TRUST_RISK_DECIDED,
        AiMemorySourceType.SYSTEM, UUID.randomUUID(), "k", "s")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> publisher.publishOnce(UUID.randomUUID(), TrustAiEventType.TRUST_RISK_DECIDED,
        AiMemorySourceType.SYSTEM, UUID.randomUUID(), "  ", "s")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void duplicatePublishReturnsExistingEvent() {
    UUID tenantId = UUID.randomUUID();
    TrustAiDomainEvent first = publish(tenantId, TrustAiEventType.TRUST_RISK_DECIDED, UUID.randomUUID(),
        "dup-key", "summary");
    TrustAiDomainEvent second = publish(tenantId, TrustAiEventType.TRUST_RISK_DECIDED, UUID.randomUUID(),
        "dup-key", "other summary");
    assertThat(second.getId()).isEqualTo(first.getId());
  }

  // ----------------------------- 3. bounded tenant-scoped batch -----------------------------

  @Test
  void pendingBatchIsBoundedAndTenantScoped() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    for (int i = 0; i < 5; i++) {
      publish(tenantA, TrustAiEventType.TRUST_RISK_DECIDED, UUID.randomUUID(), "a-" + i, "s");
    }
    publish(tenantB, TrustAiEventType.TRUST_RISK_DECIDED, UUID.randomUUID(), "b-0", "s");

    List<TrustAiDomainEvent> bounded = publisher.findPendingBatch(tenantA, 3, null);
    assertThat(bounded).hasSize(3);
    assertThat(bounded).allMatch(e -> e.getTenantId().equals(tenantA));
  }

  // ----------------------------- 4 & 5. checkpoint + idempotent processing -----------------------------

  @Test
  void processingRecordsCheckpointAndIsIdempotent() {
    UUID tenantId = UUID.randomUUID();
    OperatorCorrectionLearningRecord rec = approvedCorrection(tenantId, OperatorCorrectionType.PRODUCT_ALIAS,
        "BRK-100", "Operator mapped 'brake pad' to BRK-100");
    TrustAiDomainEvent event = events
        .findByTenantIdAndIdempotencyKey(tenantId, "operator-correction:" + rec.getId()).orElseThrow();

    TrustAiDomainEvent processed = runtime.processEvent(tenantId, event.getId());
    assertThat(processed.getStatus()).isEqualTo(TrustAiEventStatus.PROCESSED);
    assertThat(checkpoint(tenantId, event.getId()).getStatus()).isEqualTo(TrustAiProjectionStatus.COMPLETED);

    List<AiMemoryRecord> after1 =
        memory.searchMemory(tenantId, AiMemoryNamespace.PRODUCT_ALIAS_HINT, null, false, false, 25);
    assertThat(after1).hasSize(1);

    // Re-processing the same event is a no-op (no duplicate memory).
    runtime.processEvent(tenantId, event.getId());
    assertThat(memory.searchMemory(tenantId, AiMemoryNamespace.PRODUCT_ALIAS_HINT, null, false, false, 25))
        .hasSize(1);
  }

  // ----------------------------- 6 & 7. failure + retry cap dead-letter -----------------------------

  @Test
  void failingProjectorMarksEventFailedThenDeadLettersAtRetryCap() {
    UUID tenantId = UUID.randomUUID();
    // A forbidden raw-secret marker in the summary makes memory sanitization (and thus projection) fail.
    OperatorCorrectionLearningRecord rec = approvedCorrection(tenantId, OperatorCorrectionType.PRODUCT_ALIAS,
        "X", "leaking OPENAI_API_KEY=sk-secret");
    TrustAiDomainEvent event = events
        .findByTenantIdAndIdempotencyKey(tenantId, "operator-correction:" + rec.getId()).orElseThrow();

    TrustAiDomainEvent a1 = runtime.processEvent(tenantId, event.getId());
    assertThat(a1.getStatus()).isEqualTo(TrustAiEventStatus.FAILED);
    assertThat(a1.getRetryCount()).isEqualTo(1);
    assertThat(a1.getFailureCode()).isNotBlank();

    runtime.processEvent(tenantId, event.getId()); // attempt 2 -> FAILED
    TrustAiDomainEvent a3 = runtime.processEvent(tenantId, event.getId()); // attempt 3 -> DEAD_LETTERED
    assertThat(a3.getStatus()).isEqualTo(TrustAiEventStatus.DEAD_LETTERED);
    assertThat(a3.getRetryCount()).isEqualTo(3);
    assertThat(checkpoint(tenantId, event.getId()).getStatus()).isEqualTo(TrustAiProjectionStatus.FAILED);
    assertThat(checkpoint(tenantId, event.getId()).getAttemptCount()).isEqualTo(3);
  }

  // ----------------------------- 8. tenant isolation -----------------------------

  @Test
  void eventFromOneTenantCannotBeProcessedOrReadAsAnother() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TrustAiDomainEvent event = publish(tenantA, TrustAiEventType.TRUST_RISK_DECIDED, UUID.randomUUID(),
        "iso-key", "s");

    assertThatThrownBy(() -> runtime.processEvent(tenantB, event.getId()))
        .isInstanceOf(NotFoundException.class);
    assertThat(runtime.processEvent(tenantA, event.getId()).getStatus())
        .isEqualTo(TrustAiEventStatus.PROCESSED);
  }

  // ----------------------------- 17 & 16. operator correction -> memory + supersede -----------------------------

  @Test
  void operatorCorrectionProjectsHumanApprovedProductAliasMemory() {
    UUID tenantId = UUID.randomUUID();
    OperatorCorrectionLearningRecord rec = approvedCorrection(tenantId, OperatorCorrectionType.PRODUCT_ALIAS,
        "BRK-200", "Operator alias mapping");
    TrustAiDomainEvent event = events
        .findByTenantIdAndIdempotencyKey(tenantId, "operator-correction:" + rec.getId()).orElseThrow();

    runtime.processEvent(tenantId, event.getId());

    List<AiMemoryRecord> mem =
        memory.searchMemory(tenantId, AiMemoryNamespace.PRODUCT_ALIAS_HINT, null, false, false, 25);
    assertThat(mem).hasSize(1);
    assertThat(mem.get(0).getAuthorityLevel()).isEqualTo(AiMemoryAuthorityLevel.HUMAN_APPROVED);
    OperatorCorrectionLearningRecord reloaded = corrections.getCorrection(tenantId, rec.getId());
    assertThat(reloaded.getStatus()).isEqualTo(OperatorCorrectionStatus.PROJECTED_TO_MEMORY);
    assertThat(reloaded.getLinkedAiMemoryRecordId()).isEqualTo(mem.get(0).getId());
  }

  @Test
  void repeatedCorrectionSupersedesSameMemoryKey() {
    UUID tenantId = UUID.randomUUID();
    OperatorCorrectionLearningRecord r1 = approvedCorrection(tenantId, OperatorCorrectionType.PRODUCT_ALIAS,
        "SAME-VALUE", "first mapping");
    OperatorCorrectionLearningRecord r2 = approvedCorrection(tenantId, OperatorCorrectionType.PRODUCT_ALIAS,
        "SAME-VALUE", "refined mapping");
    runtime.processEvent(tenantId,
        events.findByTenantIdAndIdempotencyKey(tenantId, "operator-correction:" + r1.getId()).orElseThrow().getId());
    runtime.processEvent(tenantId,
        events.findByTenantIdAndIdempotencyKey(tenantId, "operator-correction:" + r2.getId()).orElseThrow().getId());

    // Same corrected value hash -> same deterministic memory key -> one ACTIVE record, version 2.
    List<AiMemoryRecord> mem =
        memory.searchMemory(tenantId, AiMemoryNamespace.PRODUCT_ALIAS_HINT, null, false, false, 25);
    assertThat(mem).hasSize(1);
    assertThat(mem.get(0).getVersion()).isEqualTo(2);
  }

  // ----------------------------- 18. payment hint never stores credential-like payload -----------------------------

  @Test
  void paymentEventWithCredentialLikePayloadIsRejectedAndStoresNoMemory() {
    UUID tenantId = UUID.randomUUID();
    TrustAiDomainEvent bad = publish(tenantId, TrustAiEventType.PAYMENT_OBLIGATION_UPDATED, UUID.randomUUID(),
        "pay-bad", "Authorization: Bearer sk-live-bank-credential");
    TrustAiDomainEvent processed = runtime.processEvent(tenantId, bad.getId());

    assertThat(processed.getStatus()).isEqualTo(TrustAiEventStatus.FAILED);
    assertThat(memory.searchMemory(tenantId, AiMemoryNamespace.PAYMENT_MATCH_HINT, null, true, true, 25))
        .isEmpty();

    // A safe payment summary projects a MEDIUM advisory hint.
    TrustAiDomainEvent ok = publish(tenantId, TrustAiEventType.PAYMENT_ALLOCATION_RECORDED, UUID.randomUUID(),
        "pay-ok", "Allocation reconciles obligation by external reference");
    runtime.processEvent(tenantId, ok.getId());
    List<AiMemoryRecord> mem =
        memory.searchMemory(tenantId, AiMemoryNamespace.PAYMENT_MATCH_HINT, null, false, false, 25);
    assertThat(mem).hasSize(1);
    assertThat(mem.get(0).getAuthorityLevel()).isEqualTo(AiMemoryAuthorityLevel.MEDIUM);
  }

  // ----------------------------- 19. trust risk decided is advisory-only -----------------------------

  @Test
  void trustRiskDecidedProducesOnlyLowAuthorityAdvisoryMemory() {
    UUID tenantId = UUID.randomUUID();
    TrustAiDomainEvent event = publish(tenantId, TrustAiEventType.TRUST_RISK_DECIDED, UUID.randomUUID(),
        "risk-1", "Repeated high-risk signal pattern observed for subject");
    runtime.processEvent(tenantId, event.getId());

    List<AiMemoryRecord> mem =
        memory.searchMemory(tenantId, AiMemoryNamespace.TRUST_SIGNAL_HINT, null, false, false, 25);
    assertThat(mem).hasSize(1);
    assertThat(mem.get(0).getAuthorityLevel()).isEqualTo(AiMemoryAuthorityLevel.MEDIUM);
    assertThat(mem.get(0).getAuthorityLevel()).isNotIn(
        AiMemoryAuthorityLevel.HUMAN_APPROVED, AiMemoryAuthorityLevel.HIGH);
  }

  // ----------------------------- 20. invalidation never recreates memory -----------------------------

  @Test
  void aiMemoryInvalidatedEventIsAcknowledgedAndCreatesNoMemory() {
    UUID tenantId = UUID.randomUUID();
    TrustAiDomainEvent event = publish(tenantId, TrustAiEventType.AI_MEMORY_INVALIDATED, UUID.randomUUID(),
        "inv-1", "memory invalidated");
    TrustAiDomainEvent processed = runtime.processEvent(tenantId, event.getId());

    assertThat(processed.getStatus()).isEqualTo(TrustAiEventStatus.PROCESSED);
    TrustAiProjectionCheckpoint cp = checkpoint(tenantId, event.getId());
    assertThat(cp.getStatus()).isEqualTo(TrustAiProjectionStatus.COMPLETED);
    assertThat(cp.getProjectedRecordId()).isNull();
  }

  // ----------------------------- 21. unsupported event type is skipped with checkpoint -----------------------------

  @Test
  void unsupportedEventTypeIsSkippedWithCheckpoint() {
    UUID tenantId = UUID.randomUUID();
    TrustAiDomainEvent event = publish(tenantId, TrustAiEventType.COUNTERPARTY_TRUST_UPDATED,
        UUID.randomUUID(), "cp-1", "counterparty trust updated");
    TrustAiDomainEvent processed = runtime.processEvent(tenantId, event.getId());

    assertThat(processed.getStatus()).isEqualTo(TrustAiEventStatus.SKIPPED);
    assertThat(checkpoint(tenantId, event.getId()).getStatus()).isEqualTo(TrustAiProjectionStatus.SKIPPED);
  }
}
