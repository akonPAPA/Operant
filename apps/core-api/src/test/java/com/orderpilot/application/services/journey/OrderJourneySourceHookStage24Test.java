package com.orderpilot.application.services.journey;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.OrderJourneyDtos.OrderJourneyDetailDto;
import com.orderpilot.application.services.reconciliation.InventoryReconciliationService;
import com.orderpilot.application.services.validation.ValidationRunService;
import com.orderpilot.application.services.workspace.ValidationReviewService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.extraction.ExtractedField;
import com.orderpilot.domain.extraction.ExtractedFieldRepository;
import com.orderpilot.domain.extraction.ExtractedLineItem;
import com.orderpilot.domain.extraction.ExtractedLineItemRepository;
import com.orderpilot.domain.extraction.ExtractionResult;
import com.orderpilot.domain.extraction.ExtractionResultRepository;
import com.orderpilot.domain.inventory.InventorySnapshot;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.journey.JourneySourceType;
import com.orderpilot.domain.journey.OrderJourneyRepository;
import com.orderpilot.domain.journey.events.JourneyProjectionEventStatus;
import com.orderpilot.domain.journey.events.JourneyProjectionEventType;
import com.orderpilot.domain.journey.events.OrderJourneyProjectionEvent;
import com.orderpilot.domain.journey.events.OrderJourneyProjectionEventRepository;
import com.orderpilot.domain.location.Location;
import com.orderpilot.domain.location.LocationRepository;
import com.orderpilot.domain.pricing.PriceRule;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.reconciliation.InventoryMovement;
import com.orderpilot.domain.reconciliation.InventoryMovementRepository;
import com.orderpilot.domain.reconciliation.InventoryMovementType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-24 — source-mutation hooks publish durable, idempotent, tenant-scoped journey projection events,
 * and the explicit projector then turns them into READY journeys. Exercises the REAL business services
 * (draft preparation, validation review registration, reconciliation, fulfillment signal). No AI, no
 * external write, no fake payment/carrier state, no direct journey mutation from the hook.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderJourneySourceHookStage24Test {
  private static final Instant NOW = Instant.parse("2026-06-15T00:00:00Z");

  @Autowired private ValidationRunService validationRunService;
  @Autowired private ValidationReviewService reviewService;
  @Autowired private com.orderpilot.application.services.workspace.DraftQuoteService draftQuoteService;
  @Autowired private com.orderpilot.application.services.workspace.DraftOrderService draftOrderService;
  @Autowired private InventoryReconciliationService reconciliationService;
  @Autowired private OrderJourneyService journeyService;
  @Autowired private OrderJourneyProjectionPublisher projectionPublisher;
  @Autowired private OrderJourneyProjectorRunner projectorRunner;
  @Autowired private OrderJourneyReadService readService;
  @Autowired private OrderJourneyRepository journeyRepository;
  @Autowired private OrderJourneyProjectionEventRepository events;
  @Autowired private ExtractionResultRepository extractionResults;
  @Autowired private ExtractedFieldRepository fields;
  @Autowired private ExtractedLineItemRepository lines;
  @Autowired private CustomerAccountRepository customers;
  @Autowired private ProductRepository products;
  @Autowired private LocationRepository locations;
  @Autowired private InventorySnapshotRepository inventory;
  @Autowired private PriceRuleRepository prices;
  @Autowired private InventoryMovementRepository movements;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  // ----------------------------- 1 & 4 & 6. draft quote hook -> event -> projector READY -----------------------------

  @Test
  void draftQuoteCreationPublishesOneEventAndProjectorMakesJourneyReady() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID runId = seededRun(tenantId, "SKU-HQ", "RFQ");

    UUID quoteId = draftQuoteService.createFromValidation(runId).getId();

    List<OrderJourneyProjectionEvent> quoteEvents = eventsFor(tenantId, JourneySourceType.DRAFT_QUOTE, quoteId);
    assertThat(quoteEvents).hasSize(1);
    assertThat(quoteEvents.get(0).getEventType()).isEqualTo(JourneyProjectionEventType.DRAFT_QUOTE_CREATED);
    assertThat(quoteEvents.get(0).getStatus()).isEqualTo(JourneyProjectionEventStatus.PENDING);

    projectorRunner.processTenantBatch(tenantId, 50);

    OrderJourneyDetailDto detail = readService
        .detailBySourceIfPresent(JourneySourceType.DRAFT_QUOTE, quoteId).orElseThrow();
    assertThat(detail.projectionSource()).isEqualTo("READY");
    assertThat(detail.milestones()).anyMatch(m -> m.milestoneCode().equals("QUOTE_DRAFTED")
        && m.milestoneState().equals("COMPLETED"));
  }

  // ----------------------------- 2. duplicate trigger for the same source does not duplicate the event ---------

  @Test
  void republishingTheSameSourceTriggerDoesNotCreateDuplicateProjectionEvent() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID runId = seededRun(tenantId, "SKU-IDP", "RFQ");
    UUID quoteId = draftQuoteService.createFromValidation(runId).getId();

    // the hook already published once; a duplicate trigger/retry for the same source collapses idempotently
    projectionPublisher.publishSourceEvent(tenantId, JourneyProjectionEventType.DRAFT_QUOTE_CREATED,
        JourneySourceType.DRAFT_QUOTE, quoteId, null);

    assertThat(eventsFor(tenantId, JourneySourceType.DRAFT_QUOTE, quoteId)).hasSize(1);
  }

  // ----------------------------- 3 & 5. draft order hook -> event -> projector READY -----------------------------

  @Test
  void draftOrderCreationPublishesOneEventAndProjectorMakesJourneyReady() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID runId = seededRun(tenantId, "SKU-HO", "PURCHASE_ORDER");

    UUID orderId = draftOrderService.createFromValidation(runId).getId();

    List<OrderJourneyProjectionEvent> orderEvents = eventsFor(tenantId, JourneySourceType.DRAFT_ORDER, orderId);
    assertThat(orderEvents).hasSize(1);
    assertThat(orderEvents.get(0).getEventType()).isEqualTo(JourneyProjectionEventType.DRAFT_ORDER_CREATED);

    projectorRunner.processTenantBatch(tenantId, 50);

    OrderJourneyDetailDto detail = readService
        .detailBySourceIfPresent(JourneySourceType.DRAFT_ORDER, orderId).orElseThrow();
    assertThat(detail.projectionSource()).isEqualTo("READY");
    assertThat(detail.milestones()).anyMatch(m -> m.milestoneCode().equals("ORDER_DRAFTED")
        && m.milestoneState().equals("COMPLETED"));
  }

  // ----------------------------- validation review registration hook -----------------------------

  @Test
  void validationReviewRegistrationPublishesEvent() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID reviewCaseId = readyReviewCase(tenantId, "SKU-VR", "RFQ");

    List<OrderJourneyProjectionEvent> reviewEvents =
        eventsFor(tenantId, JourneySourceType.VALIDATION_REVIEW, reviewCaseId);
    assertThat(reviewEvents).hasSize(1);
    assertThat(reviewEvents.get(0).getEventType())
        .isEqualTo(JourneyProjectionEventType.VALIDATION_REVIEW_REGISTERED);

    projectorRunner.processTenantBatch(tenantId, 50);
    assertThat(journeyRepository
        .findByTenantIdAndSourceTypeAndSourceId(tenantId, JourneySourceType.VALIDATION_REVIEW, reviewCaseId))
        .isPresent();
  }

  // ----------------------------- reconciliation case create hook -----------------------------

  @Test
  void reconciliationCaseCreationPublishesEventAndProjectsBlockedJourney() {
    UUID tenantId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    addMovement(tenantId, productId, locationId, InventoryMovementType.OPENING_STOCK, "150", "2026-05-01T00:00:00Z");
    addMovement(tenantId, productId, locationId, InventoryMovementType.SALE, "34", "2026-05-02T00:00:00Z");
    addMovement(tenantId, productId, locationId, InventoryMovementType.ACTUAL_STOCK_COUNT, "100", "2026-05-03T00:00:00Z");

    UUID caseId = reconciliationService.runInventoryReconciliation(productId, locationId).reconciliationCaseId();
    assertThat(caseId).isNotNull();

    List<OrderJourneyProjectionEvent> reconEvents =
        eventsFor(tenantId, JourneySourceType.RECONCILIATION_CASE, caseId);
    assertThat(reconEvents).hasSize(1);
    assertThat(reconEvents.get(0).getEventType())
        .isEqualTo(JourneyProjectionEventType.RECONCILIATION_CASE_CREATED);

    projectorRunner.processTenantBatch(tenantId, 50);
    OrderJourneyDetailDto detail = readService
        .detailBySourceIfPresent(JourneySourceType.RECONCILIATION_CASE, caseId).orElseThrow();
    assertThat(detail.projectionSource()).isEqualTo("READY");
    assertThat(detail.blocked()).isTrue();
    // a reconciliation journey never fabricates a payment milestone
    assertThat(detail.milestones()).anyMatch(m -> m.milestoneCode().equals("PAYMENT_CONFIRMED")
        && m.milestoneState().equals("UNKNOWN"));
  }

  // ----------------------------- 7 & 8. fulfillment signal hook (no payment milestone, idempotent) ---------

  @Test
  void fulfillmentSignalRecordingPublishesEventWithoutPaymentMilestoneAndIsIdempotent() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID runId = seededRun(tenantId, "SKU-SIG", "RFQ");
    UUID draftId = draftQuoteService.createFromValidation(runId).getId();
    projectorRunner.processTenantBatch(tenantId, 50);
    UUID journeyId = journeyRepository
        .findByTenantIdAndSourceTypeAndSourceId(tenantId, JourneySourceType.DRAFT_QUOTE, draftId)
        .orElseThrow().getId();

    journeyService.recordSignal(journeyId, new com.orderpilot.api.dto.OrderJourneyDtos
        .RecordFulfillmentSignalRequest("INTERNAL", "PACKED", "OK", new BigDecimal("0.990"), "wh-ref-1",
        null, true), UUID.randomUUID());

    // one durable FULFILLMENT_SIGNAL_RECORDED event for the underlying draft-quote source
    List<OrderJourneyProjectionEvent> signalEvents = events
        .findByTenantIdOrderByOccurredAtDesc(tenantId, org.springframework.data.domain.PageRequest.of(0, 100))
        .stream().filter(e -> e.getEventType() == JourneyProjectionEventType.FULFILLMENT_SIGNAL_RECORDED).toList();
    assertThat(signalEvents).hasSize(1);

    // processing the signal event is idempotent and never fabricates a payment milestone
    projectorRunner.processTenantBatch(tenantId, 50);
    OrderJourneyDetailDto detail = readService
        .detailBySourceIfPresent(JourneySourceType.DRAFT_QUOTE, draftId).orElseThrow();
    assertThat(detail.fulfillmentSignals()).hasSize(1);
    assertThat(detail.milestones()).anyMatch(m -> m.milestoneCode().equals("PACKED")
        && m.milestoneState().equals("COMPLETED"));
    assertThat(detail.milestones()).anyMatch(m -> m.milestoneCode().equals("PAYMENT_CONFIRMED")
        && m.milestoneState().equals("UNKNOWN"));
    assertThat(detail.paymentStatusAvailable()).isFalse();
  }

  // ----------------------------- 9. tenant isolation -----------------------------

  @Test
  void tenantAHookDoesNotCreateTenantBJourney() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    UUID runId = seededRun(tenantA, "SKU-TEN", "RFQ");
    UUID draftId = draftQuoteService.createFromValidation(runId).getId();

    // tenant B's projector batch sees none of tenant A's events; no journey leaks
    TenantContext.setTenantId(tenantB);
    projectorRunner.processTenantBatch(tenantB, 50);
    assertThat(journeyRepository.countByTenantId(tenantB)).isZero();
    assertThat(readService.detailBySourceIfPresent(JourneySourceType.DRAFT_QUOTE, draftId)).isEmpty();
  }

  // ----------------------------- fixtures -----------------------------

  private List<OrderJourneyProjectionEvent> eventsFor(UUID tenantId, JourneySourceType sourceType, UUID sourceId) {
    return events.findByTenantIdOrderByOccurredAtDesc(tenantId, org.springframework.data.domain.PageRequest.of(0, 100))
        .stream().filter(e -> e.getSourceType() == sourceType && sourceId.equals(e.getSourceId())).toList();
  }

  /** Seeds master data + an extraction and runs validation, returning the extraction + validation-run ids. */
  private UUID[] seed(UUID tenantId, String sku, String intent) {
    Product product = product(tenantId, sku);
    Location location = location(tenantId, sku);
    customer(tenantId, sku, location.getId());
    inventory.save(new InventorySnapshot(tenantId, product.getId(), location.getId(), new BigDecimal("12"),
        new BigDecimal("12"), BigDecimal.ZERO, NOW, "TEST", null, NOW));
    prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA",
        new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    ExtractionResult extraction = extraction(tenantId, sku, intent);
    UUID runId = validationRunService.run(extraction.getId(), "FULL").getId();
    return new UUID[] {extraction.getId(), runId};
  }

  /** Seeds + runs validation, returning the validation run id (for direct draft creation). */
  private UUID seededRun(UUID tenantId, String sku, String intent) {
    return seed(tenantId, sku, intent)[1];
  }

  /** Registers a validation-review case (ExceptionCase) for a freshly seeded run, returning its id. */
  private UUID readyReviewCase(UUID tenantId, String sku, String intent) {
    UUID extractionId = seed(tenantId, sku, intent)[0];
    return reviewService.createForExtractionResult(extractionId).reviewCase().id();
  }

  private ExtractionResult extraction(UUID tenantId, String sku, String intent) {
    ExtractionResult result = extractionResults.save(new ExtractionResult(tenantId, UUID.randomUUID(),
        "CHANNEL_MESSAGE", UUID.randomUUID(), intent, "message", new BigDecimal("0.95"), "{}",
        "ready_for_validation", NOW));
    fields.save(new ExtractedField(tenantId, result.getId(), "customer_hint", "Acme " + sku, "Acme " + sku,
        "customer_hint", new BigDecimal("0.95"), null, NOW));
    lines.save(new ExtractedLineItem(tenantId, result.getId(), 1, sku, sku + " Filter", "2",
        new BigDecimal("2"), "EA", "EA", new BigDecimal("0.95"), null, NOW));
    return result;
  }

  private CustomerAccount customer(UUID tenantId, String sku, UUID defaultLocationId) {
    return customers.save(new CustomerAccount(tenantId, null, "ACME-" + sku, "Acme " + sku, "Acme " + sku,
        null, "ACTIVE", "USD", defaultLocationId, NOW));
  }

  private Product product(UUID tenantId, String sku) {
    return products.save(new Product(tenantId, sku, sku + " Filter", sku + " Filter", "Filters", "Brand",
        "Maker", "EA", "ACTIVE", new BigDecimal("10"), "USD", NOW));
  }

  private Location location(UUID tenantId, String code) {
    return locations.save(new Location(tenantId, code, code, "WAREHOUSE", null, "Almaty", "KZ", true, NOW));
  }

  private void addMovement(UUID tenantId, UUID productId, UUID locationId, InventoryMovementType type,
      String quantity, String occurredAt) {
    movements.save(new InventoryMovement(tenantId, productId, locationId, type, new BigDecimal(quantity),
        Instant.parse(occurredAt), "TEST", type.name(), Instant.parse(occurredAt)));
  }
}
