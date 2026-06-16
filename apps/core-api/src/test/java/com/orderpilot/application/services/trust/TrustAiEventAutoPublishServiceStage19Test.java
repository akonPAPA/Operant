package com.orderpilot.application.services.trust;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.domain.trust.events.TrustAiDomainEvent;
import com.orderpilot.domain.trust.events.TrustAiDomainEventRepository;
import com.orderpilot.domain.trust.events.TrustAiEventType;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-19 Layer A — Transactional Trust/AI Event Auto-Publishing Hooks.
 *
 * Verifies that the deterministic hook adapter publishes the correct, bounded, sanitized, tenant-scoped
 * event exactly once per source record, is idempotent on repeat, and never leaks raw/stack-trace-shaped
 * payloads. Integration wiring (17D risk decisions, 17A document trust) is covered by the respective
 * stage tests; this isolates the publishing contract.
 */
@SpringBootTest
@ActiveProfiles("test")
class TrustAiEventAutoPublishServiceStage19Test {
  @Autowired private TrustAiEventAutoPublishService autoPublish;
  @Autowired private TrustAiDomainEventRepository events;

  private List<TrustAiDomainEvent> eventsFor(UUID tenantId, TrustAiEventType type) {
    return events.findByTenantIdAndEventTypeOrderByOccurredAtDesc(tenantId, type, PageRequest.of(0, 50));
  }

  // ----------------------------- 1. document trust completion -----------------------------

  @Test
  void documentTrustCompletionPublishesEventOnce() {
    UUID tenantId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    autoPublish.publishDocumentTrustCompleted(tenantId, runId, "Document trust run completed: LOW (2 signals)");

    List<TrustAiDomainEvent> published = eventsFor(tenantId, TrustAiEventType.DOCUMENT_TRUST_COMPLETED);
    assertThat(published).hasSize(1);
    assertThat(published.get(0).getSourceId()).isEqualTo(runId);
    assertThat(published.get(0).getIdempotencyKey()).isEqualTo("document-trust-completed:" + runId);
  }

  // ----------------------------- 2. counterparty trust update -----------------------------

  @Test
  void counterpartyTrustUpdatePublishesEventOnce() {
    UUID tenantId = UUID.randomUUID();
    UUID profileId = UUID.randomUUID();
    autoPublish.publishCounterpartyTrustUpdated(tenantId, profileId, 3L, "Counterparty trust tier MEDIUM");

    List<TrustAiDomainEvent> published = eventsFor(tenantId, TrustAiEventType.COUNTERPARTY_TRUST_UPDATED);
    assertThat(published).hasSize(1);
    assertThat(published.get(0).getIdempotencyKey()).isEqualTo("counterparty-trust-updated:" + profileId + ":3");
  }

  // ----------------------------- 3 & 4. payment obligation + allocation -----------------------------

  @Test
  void paymentObligationAndAllocationPublishDistinctEventsOnce() {
    UUID tenantId = UUID.randomUUID();
    UUID obligationId = UUID.randomUUID();
    UUID allocationId = UUID.randomUUID();
    autoPublish.publishPaymentObligationUpdated(tenantId, obligationId, 1L, "Obligation PARTIALLY_PAID");
    autoPublish.publishPaymentAllocationRecorded(tenantId, allocationId, "Allocation recorded 100.00");

    assertThat(eventsFor(tenantId, TrustAiEventType.PAYMENT_OBLIGATION_UPDATED)).hasSize(1);
    assertThat(eventsFor(tenantId, TrustAiEventType.PAYMENT_ALLOCATION_RECORDED)).hasSize(1);
  }

  // ----------------------------- 5 & 6. risk decided + overridden -----------------------------

  @Test
  void trustRiskDecidedAndOverriddenPublishEventsOnce() {
    UUID tenantId = UUID.randomUUID();
    UUID decisionId = UUID.randomUUID();
    UUID overrideId = UUID.randomUUID();
    autoPublish.publishTrustRiskDecided(tenantId, decisionId, "Trust risk decided HIGH");
    autoPublish.publishTrustRiskOverridden(tenantId, decisionId, overrideId, "Trust risk overridden HIGH -> MEDIUM");

    assertThat(eventsFor(tenantId, TrustAiEventType.TRUST_RISK_DECIDED)).hasSize(1);
    List<TrustAiDomainEvent> overridden = eventsFor(tenantId, TrustAiEventType.TRUST_RISK_OVERRIDDEN);
    assertThat(overridden).hasSize(1);
    assertThat(overridden.get(0).getIdempotencyKey())
        .isEqualTo("trust-risk-overridden:" + decisionId + ":" + overrideId);
  }

  // ----------------------------- 7. duplicate hook is idempotent -----------------------------

  @Test
  void duplicateHookCallDoesNotDuplicateEvent() {
    UUID tenantId = UUID.randomUUID();
    UUID decisionId = UUID.randomUUID();
    autoPublish.publishTrustRiskDecided(tenantId, decisionId, "Trust risk decided CRITICAL");
    autoPublish.publishTrustRiskDecided(tenantId, decisionId, "Trust risk decided CRITICAL");

    assertThat(eventsFor(tenantId, TrustAiEventType.TRUST_RISK_DECIDED)).hasSize(1);
  }

  // ----------------------------- 8. summary is bounded/sanitized -----------------------------

  @Test
  void summaryIsCollapsedAndBounded() {
    UUID tenantId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    String messy = "line one\n\tline two   with   spaces";
    autoPublish.publishDocumentTrustCompleted(tenantId, runId, messy);

    TrustAiDomainEvent event = eventsFor(tenantId, TrustAiEventType.DOCUMENT_TRUST_COMPLETED).get(0);
    assertThat(event.getPayloadSummary()).isEqualTo("line one line two with spaces");
    assertThat(event.getPayloadSummary()).doesNotContain("\n");
    assertThat(event.getPayloadSummary().length()).isLessThanOrEqualTo(TrustAiEventAutoPublishService.MAX_SUMMARY);
  }

  // ----------------------------- 9. stack-trace-shaped payload rejected -----------------------------

  @Test
  void stackTraceShapedSummaryIsNotPublished() {
    UUID tenantId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    autoPublish.publishDocumentTrustCompleted(tenantId, runId,
        "Exception in thread main java.lang.NullPointerException at Foo.java:42");

    TrustAiDomainEvent event = eventsFor(tenantId, TrustAiEventType.DOCUMENT_TRUST_COMPLETED).get(0);
    assertThat(event.getPayloadSummary()).isNull();
  }

  // ----------------------------- 9b. no raw value stored on the entity -----------------------------

  @Test
  void eventEntityHasNoRawPayloadField() {
    for (Field field : TrustAiDomainEvent.class.getDeclaredFields()) {
      String name = field.getName().toLowerCase(Locale.ROOT);
      assertThat(name).doesNotContain("body");
      assertThat(name).doesNotContain("rawprompt");
      assertThat(name).doesNotContain("rawtext");
    }
  }

  // ----------------------------- 10. tenant isolation -----------------------------

  @Test
  void hookForTenantADoesNotPublishUnderTenantB() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    autoPublish.publishDocumentTrustCompleted(tenantA, runId, "completed");

    assertThat(eventsFor(tenantA, TrustAiEventType.DOCUMENT_TRUST_COMPLETED)).hasSize(1);
    assertThat(eventsFor(tenantB, TrustAiEventType.DOCUMENT_TRUST_COMPLETED)).isEmpty();
  }
}
