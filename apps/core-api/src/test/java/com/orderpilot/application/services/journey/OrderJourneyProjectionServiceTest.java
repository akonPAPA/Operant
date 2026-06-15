package com.orderpilot.application.services.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneyDetailDto;
import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneyMilestoneDto;
import com.orderpilot.api.dto.OrderJourneyProjectionDtos.JourneyProjectionRequestResponse;
import com.orderpilot.api.dto.OrderJourneyProjectionDtos.ProcessJourneyProjectionResponse;
import com.orderpilot.application.services.journey.OrderJourneyProjectionPublisher.PublishCommand;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.journey.JourneySourceType;
import com.orderpilot.domain.journey.OrderJourney;
import com.orderpilot.domain.journey.OrderJourneyRepository;
import com.orderpilot.domain.journey.events.JourneyProjectionCheckpointStatus;
import com.orderpilot.domain.journey.events.JourneyProjectionEventStatus;
import com.orderpilot.domain.journey.events.JourneyProjectionEventType;
import com.orderpilot.domain.journey.events.OrderJourneyProjectionCheckpoint;
import com.orderpilot.domain.journey.events.OrderJourneyProjectionCheckpointRepository;
import com.orderpilot.domain.journey.events.OrderJourneyProjectionEvent;
import com.orderpilot.domain.journey.events.OrderJourneyProjectionEventRepository;
import com.orderpilot.domain.workspace.DraftOrder;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuote;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-23 Event/Outbox-driven Order Journey Projector Runtime — idempotent projection, duplicate-event
 * tolerance, tenant isolation, safe SKIPPED vs FAILED outcomes, missing-source handling, no fabricated
 * payment milestones, checkpoint uniqueness, and the by-source no-duplicate guarantee after the projector
 * has run. Uses {@code @SpringBootTest} (like the OP-CAP-18 trust runtime test) because processing runs each
 * event in a {@code REQUIRES_NEW} transaction; each test uses fresh random tenant ids for isolation.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderJourneyProjectionServiceTest {
  @Autowired private OrderJourneyProjectionPublisher publisher;
  @Autowired private OrderJourneyProjectorRunner runner;
  @Autowired private OrderJourneyReadService readService;
  @Autowired private OrderJourneyService journeyService;
  @Autowired private OrderJourneyProjectionEventRepository events;
  @Autowired private OrderJourneyProjectionCheckpointRepository checkpoints;
  @Autowired private OrderJourneyRepository journeyRepository;
  @Autowired private DraftQuoteRepository draftQuoteRepository;
  @Autowired private DraftOrderRepository draftOrderRepository;

  private static final Instant NOW = Instant.parse("2026-06-15T00:00:00Z");

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  // ----------------------------- fixtures -----------------------------

  private UUID draftQuote(UUID tenantId, String number, String status) {
    return draftQuoteRepository.save(new DraftQuote(tenantId, number, UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), status, "USD", null, NOW)).getId();
  }

  private UUID draftOrder(UUID tenantId, String number, String status) {
    return draftOrderRepository.save(new DraftOrder(tenantId, number, UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), status, "USD", null, NOW)).getId();
  }

  private OrderJourneyProjectionEvent publish(UUID tenantId, JourneyProjectionEventType type,
      JourneySourceType sourceType, UUID sourceId, String idemKey) {
    return publisher.publish(new PublishCommand(tenantId, type, sourceType, sourceId, "TEST", null, null,
        idemKey, "summary", null));
  }

  private OrderJourneyProjectionEvent reload(UUID tenantId, UUID eventId) {
    return events.findByIdAndTenantId(eventId, tenantId).orElseThrow();
  }

  private OrderJourneyMilestoneDto milestone(OrderJourneyDetailDto detail, String code) {
    return detail.milestones().stream().filter(m -> m.milestoneCode().equals(code)).findFirst().orElseThrow();
  }

  // ----------------------------- 1. draft quote event projects a journey -----------------------------

  @Test
  void projectorProcessesDraftQuoteEventAndCreatesJourney() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuote(tenantId, "Q-PQ", "APPROVED");
    UUID eventId = publish(tenantId, JourneyProjectionEventType.DRAFT_QUOTE_CREATED,
        JourneySourceType.DRAFT_QUOTE, quoteId, "evt-pq").getId();

    ProcessJourneyProjectionResponse res = runner.processTenantBatch(tenantId, 50);

    assertThat(res.processed()).isEqualTo(1);
    assertThat(reload(tenantId, eventId).getStatus()).isEqualTo(JourneyProjectionEventStatus.PROCESSED);
    OrderJourney journey = journeyRepository
        .findByTenantIdAndSourceTypeAndSourceId(tenantId, JourneySourceType.DRAFT_QUOTE, quoteId).orElseThrow();
    OrderJourneyDetailDto detail = readService.detail(journey.getId());
    assertThat(milestone(detail, "QUOTE_DRAFTED").milestoneState()).isEqualTo("COMPLETED");
    OrderJourneyProjectionCheckpoint cp = checkpoints
        .findByTenantIdAndProjectorNameAndEventId(tenantId, OrderJourneyProjector.PROJECTOR_NAME, eventId)
        .orElseThrow();
    assertThat(cp.getStatus()).isEqualTo(JourneyProjectionCheckpointStatus.COMPLETED);
    assertThat(cp.getProjectedRecordId()).isEqualTo(journey.getId());
  }

  // ----------------------------- 2. draft order event projects a journey -----------------------------

  @Test
  void projectorProcessesDraftOrderEventAndCreatesJourney() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID orderId = draftOrder(tenantId, "O-PO", "CONFIRMED");
    publish(tenantId, JourneyProjectionEventType.DRAFT_ORDER_CREATED,
        JourneySourceType.DRAFT_ORDER, orderId, "evt-po");

    runner.processTenantBatch(tenantId, 50);

    OrderJourney journey = journeyRepository
        .findByTenantIdAndSourceTypeAndSourceId(tenantId, JourneySourceType.DRAFT_ORDER, orderId).orElseThrow();
    OrderJourneyDetailDto detail = readService.detail(journey.getId());
    assertThat(milestone(detail, "ORDER_DRAFTED").milestoneState()).isEqualTo("COMPLETED");
    assertThat(milestone(detail, "ORDER_CONFIRMED").milestoneState()).isEqualTo("COMPLETED");
  }

  // ----------------------------- 3 & 9. idempotent / checkpoint uniqueness -----------------------------

  @Test
  void duplicateProcessingIsIdempotentAndKeepsOneCheckpoint() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuote(tenantId, "Q-IDEM", "DRAFT");
    UUID eventId = publish(tenantId, JourneyProjectionEventType.DRAFT_QUOTE_CREATED,
        JourneySourceType.DRAFT_QUOTE, quoteId, "evt-idem").getId();

    runner.processEvent(tenantId, eventId);
    // second processing of the SAME event must be a no-op
    runner.processEvent(tenantId, eventId);

    assertThat(reload(tenantId, eventId).getStatus()).isEqualTo(JourneyProjectionEventStatus.PROCESSED);
    // exactly one journey for the source, milestones not duplicated
    assertThat(journeyRepository.countByTenantId(tenantId)).isEqualTo(1);
    OrderJourney journey = journeyRepository
        .findByTenantIdAndSourceTypeAndSourceId(tenantId, JourneySourceType.DRAFT_QUOTE, quoteId).orElseThrow();
    OrderJourneyDetailDto detail = readService.detail(journey.getId());
    long requestReceived = detail.milestones().stream()
        .filter(m -> m.milestoneCode().equals("REQUEST_RECEIVED")).count();
    assertThat(requestReceived).isEqualTo(1);
    // exactly one checkpoint (the unique (tenant, projector, event) guard holds)
    assertThat(checkpoints.findByTenantIdAndProjectorNameAndEventId(
        tenantId, OrderJourneyProjector.PROJECTOR_NAME, eventId)).isPresent();
  }

  // ----------------------------- 4. tenant isolation -----------------------------

  @Test
  void tenantAEventCannotBeProcessedOrProjectedByTenantB() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    UUID quoteId = draftQuote(tenantA, "Q-TEN", "DRAFT");
    UUID eventId = publish(tenantA, JourneyProjectionEventType.DRAFT_QUOTE_CREATED,
        JourneySourceType.DRAFT_QUOTE, quoteId, "evt-ten").getId();

    // tenant B's batch sees nothing; addressing tenant A's event by id under tenant B is rejected
    TenantContext.setTenantId(tenantB);
    assertThat(runner.processTenantBatch(tenantB, 50).fetched()).isZero();
    assertThatThrownBy(() -> runner.processEvent(tenantB, eventId)).isInstanceOf(NotFoundException.class);

    // tenant A's event is still pending and untouched; no journey leaked to tenant B
    assertThat(reload(tenantA, eventId).getStatus()).isEqualTo(JourneyProjectionEventStatus.PENDING);
    assertThat(journeyRepository.countByTenantId(tenantB)).isZero();
  }

  // ----------------------------- 5. unsupported source -> SKIPPED -----------------------------

  @Test
  void unsupportedSourceTypeIsSkippedNotFailed() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID eventId = publish(tenantId, JourneyProjectionEventType.ORDER_JOURNEY_REFRESH_REQUESTED,
        JourneySourceType.EXTERNAL_MIRROR, UUID.randomUUID(), "evt-ext").getId();

    runner.processEvent(tenantId, eventId);

    assertThat(reload(tenantId, eventId).getStatus()).isEqualTo(JourneyProjectionEventStatus.SKIPPED);
    OrderJourneyProjectionCheckpoint cp = checkpoints
        .findByTenantIdAndProjectorNameAndEventId(tenantId, OrderJourneyProjector.PROJECTOR_NAME, eventId)
        .orElseThrow();
    assertThat(cp.getStatus()).isEqualTo(JourneyProjectionCheckpointStatus.SKIPPED);
    assertThat(cp.getFailureCode()).isEqualTo("UNSUPPORTED_SOURCE");
  }

  // ----------------------------- 6. invalid payload -> FAILED -----------------------------

  @Test
  void invalidPayloadWithNoSourceIdFailsWithBoundedReason() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID eventId = publish(tenantId, JourneyProjectionEventType.ORDER_JOURNEY_REFRESH_REQUESTED,
        JourneySourceType.DRAFT_QUOTE, null, "evt-invalid").getId();

    runner.processEvent(tenantId, eventId);

    assertThat(reload(tenantId, eventId).getStatus()).isEqualTo(JourneyProjectionEventStatus.FAILED);
    OrderJourneyProjectionCheckpoint cp = checkpoints
        .findByTenantIdAndProjectorNameAndEventId(tenantId, OrderJourneyProjector.PROJECTOR_NAME, eventId)
        .orElseThrow();
    assertThat(cp.getStatus()).isEqualTo(JourneyProjectionCheckpointStatus.FAILED);
    assertThat(cp.getFailureCode()).isEqualTo("INVALID_PAYLOAD");
    assertThat(cp.getFailureMessage()).isNotBlank();
  }

  // ----------------------------- 7. missing source -> SKIPPED -----------------------------

  @Test
  void missingSourceIsSkippedGracefully() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID eventId = publish(tenantId, JourneyProjectionEventType.DRAFT_QUOTE_CREATED,
        JourneySourceType.DRAFT_QUOTE, UUID.randomUUID(), "evt-missing").getId();

    runner.processEvent(tenantId, eventId);

    assertThat(reload(tenantId, eventId).getStatus()).isEqualTo(JourneyProjectionEventStatus.SKIPPED);
    assertThat(checkpoints.findByTenantIdAndProjectorNameAndEventId(
        tenantId, OrderJourneyProjector.PROJECTOR_NAME, eventId).orElseThrow().getFailureCode())
        .isEqualTo("SOURCE_NOT_FOUND");
    assertThat(journeyRepository.countByTenantId(tenantId)).isZero();
  }

  // ----------------------------- 8. fulfillment-signal-typed projection keeps payment UNKNOWN ---------

  @Test
  void projectionNeverFabricatesPaymentMilestones() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuote(tenantId, "Q-PAY", "APPROVED");
    publish(tenantId, JourneyProjectionEventType.FULFILLMENT_SIGNAL_RECORDED,
        JourneySourceType.DRAFT_QUOTE, quoteId, "evt-pay");

    runner.processTenantBatch(tenantId, 50);

    OrderJourney journey = journeyRepository
        .findByTenantIdAndSourceTypeAndSourceId(tenantId, JourneySourceType.DRAFT_QUOTE, quoteId).orElseThrow();
    OrderJourneyDetailDto detail = readService.detail(journey.getId());
    assertThat(milestone(detail, "PAYMENT_PENDING").milestoneState()).isEqualTo("UNKNOWN");
    assertThat(milestone(detail, "PAYMENT_CONFIRMED").milestoneState()).isEqualTo("UNKNOWN");
    assertThat(detail.paymentStatusAvailable()).isFalse();
  }

  // ----------------------------- 10. by-source after projector does not duplicate -----------------------------

  @Test
  void bySourceReadAfterProjectorDoesNotDuplicateProjection() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuote(tenantId, "Q-BS", "DRAFT");
    publish(tenantId, JourneyProjectionEventType.DRAFT_QUOTE_CREATED,
        JourneySourceType.DRAFT_QUOTE, quoteId, "evt-bs");
    runner.processTenantBatch(tenantId, 50);
    assertThat(journeyRepository.countByTenantId(tenantId)).isEqualTo(1);

    // the projector already prepared the projection: a by-source read finds it READY without materializing
    OrderJourneyDetailDto ready = readService
        .detailBySourceIfPresent(JourneySourceType.DRAFT_QUOTE, quoteId).orElseThrow();
    assertThat(ready.projectionSource()).isEqualTo("READY");
    // and re-ensuring the same source is idempotent (still one journey)
    journeyService.ensureJourney(JourneySourceType.DRAFT_QUOTE, quoteId);
    assertThat(journeyRepository.countByTenantId(tenantId)).isEqualTo(1);
  }

  // ----------------------------- requestProjection is idempotent + audited path -----------------------------

  @Test
  void requestProjectionIsIdempotentPerSource() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteId = draftQuote(tenantId, "Q-REQ", "DRAFT");

    JourneyProjectionRequestResponse first =
        runner.requestProjection(tenantId, JourneySourceType.DRAFT_QUOTE, quoteId, "MANUAL", null);
    JourneyProjectionRequestResponse second =
        runner.requestProjection(tenantId, JourneySourceType.DRAFT_QUOTE, quoteId, "MANUAL", null);

    assertThat(first.alreadyExisted()).isFalse();
    assertThat(second.alreadyExisted()).isTrue();
    assertThat(second.eventId()).isEqualTo(first.eventId());
    // the requested refresh is now processable end-to-end
    runner.processTenantBatch(tenantId, 50);
    assertThat(journeyRepository
        .findByTenantIdAndSourceTypeAndSourceId(tenantId, JourneySourceType.DRAFT_QUOTE, quoteId)).isPresent();
  }
}
