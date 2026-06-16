package com.orderpilot.application.services.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.Stage6Dtos.DraftLineCorrectionRequest;
import com.orderpilot.api.dto.Stage6Dtos.DraftOrderDetail;
import com.orderpilot.api.dto.Stage6Dtos.DraftOrderLineView;
import com.orderpilot.api.dto.Stage6Dtos.DraftPreparationResult;
import com.orderpilot.api.dto.Stage6Dtos.DraftQuoteDetail;
import com.orderpilot.api.dto.Stage6Dtos.DraftQuoteLineView;
import com.orderpilot.api.dto.Stage6Dtos.ReviewActionRequest;
import com.orderpilot.application.services.validation.ValidationRunService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
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
import com.orderpilot.domain.location.Location;
import com.orderpilot.domain.location.LocationRepository;
import com.orderpilot.domain.pricing.DiscountRuleRepository;
import com.orderpilot.domain.pricing.MarginRuleRepository;
import com.orderpilot.domain.pricing.PriceRule;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.workspace.DraftOrderLineRepository;
import com.orderpilot.domain.workspace.DraftQuoteLineRepository;
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
 * OP-CAP-09B Line-Level Operator Draft Review Workspace Foundation.
 * Builds drafts through the real 09A path (validation run -> review case -> prepare-draft), then exercises
 * bounded detail, bounded line corrections, the conservative mark-ready transition, tenant isolation, audit,
 * and the internal-only / no-master-data-mutation contract.
 */
@SpringBootTest
@ActiveProfiles("test")
class DraftLineReviewStage9BTest {
  private static final Instant NOW = Instant.parse("2026-06-07T00:00:00Z");

  @Autowired private ValidationRunService validationRunService;
  @Autowired private ValidationReviewService reviewService;
  @Autowired private DraftCommandPreparationService draftPreparationService;
  @Autowired private DraftReviewService draftReviewService;
  @Autowired private ExtractionResultRepository extractionResults;
  @Autowired private ExtractedFieldRepository fields;
  @Autowired private ExtractedLineItemRepository lines;
  @Autowired private CustomerAccountRepository customers;
  @Autowired private ProductRepository products;
  @Autowired private LocationRepository locations;
  @Autowired private InventorySnapshotRepository inventory;
  @Autowired private PriceRuleRepository prices;
  @Autowired private DiscountRuleRepository discounts;
  @Autowired private MarginRuleRepository margins;
  @Autowired private DraftQuoteLineRepository quoteLines;
  @Autowired private DraftOrderLineRepository orderLines;
  @Autowired private AuditEventRepository auditEvents;
  @Autowired private ObjectMapper objectMapper;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void retrievesBoundedDraftQuoteDetailWithLines() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    DraftPreparationResult prepared = prepareQuote(tenantId, List.of("SKU-Q1", "SKU-Q2"));

    DraftQuoteDetail detail = draftReviewService.quoteDetail(prepared.draftId());

    assertThat(detail.draftId()).isEqualTo(prepared.draftId());
    assertThat(detail.sourceReviewCaseId()).isEqualTo(prepared.sourceHandoffId());
    assertThat(detail.sourceValidationRunId()).isNotNull();
    assertThat(detail.externalExecution()).isEqualTo("DISABLED");
    assertThat(detail.lineCount()).isEqualTo(2);
    assertThat(detail.lines()).extracting(DraftQuoteLineView::quantity).allMatch(q -> q.compareTo(BigDecimal.ZERO) > 0);
  }

  @Test
  void retrievesBoundedDraftOrderDetailWithLines() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    DraftPreparationResult prepared = prepareOrder(tenantId, List.of("SKU-O1"));

    DraftOrderDetail detail = draftReviewService.orderDetail(prepared.draftId());

    assertThat(detail.draftId()).isEqualTo(prepared.draftId());
    assertThat(detail.sourceReviewCaseId()).isEqualTo(prepared.sourceHandoffId());
    assertThat(detail.externalExecution()).isEqualTo("DISABLED");
    assertThat(detail.lineCount()).isEqualTo(1);
  }

  @Test
  void tenantCannotReadAnotherTenantDraft() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    DraftPreparationResult prepared = prepareQuote(tenantA, List.of("SKU-TR"));

    TenantContext.setTenantId(UUID.randomUUID());
    assertThatThrownBy(() -> draftReviewService.quoteDetail(prepared.draftId()))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void tenantCannotCorrectAnotherTenantLine() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    DraftPreparationResult prepared = prepareQuote(tenantA, List.of("SKU-TC"));
    UUID lineId = draftReviewService.quoteDetail(prepared.draftId()).lines().get(0).lineId();

    TenantContext.setTenantId(UUID.randomUUID());
    assertThatThrownBy(() -> draftReviewService.correctQuoteLine(prepared.draftId(), lineId, new DraftLineCorrectionRequest(new BigDecimal("3"), null, null, null, null, null, UUID.randomUUID())))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void correctingQuoteLineUpdatesOnlyThatLine() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    DraftPreparationResult prepared = prepareQuote(tenantId, List.of("SKU-A", "SKU-B"));
    List<DraftQuoteLineView> before = draftReviewService.quoteDetail(prepared.draftId()).lines();
    DraftQuoteLineView target = before.get(0);
    DraftQuoteLineView other = before.get(1);

    DraftQuoteDetail updated = draftReviewService.correctQuoteLine(prepared.draftId(), target.lineId(),
        new DraftLineCorrectionRequest(new BigDecimal("7"), "BOX", "Corrected description", new BigDecimal("12.50"), null, "operator fixed qty", UUID.randomUUID()));

    DraftQuoteLineView updatedTarget = updated.lines().stream().filter(l -> l.lineId().equals(target.lineId())).findFirst().orElseThrow();
    DraftQuoteLineView untouched = updated.lines().stream().filter(l -> l.lineId().equals(other.lineId())).findFirst().orElseThrow();
    assertThat(updatedTarget.quantity()).isEqualByComparingTo("7");
    assertThat(updatedTarget.uom()).isEqualTo("BOX");
    assertThat(updatedTarget.description()).isEqualTo("Corrected description");
    assertThat(updatedTarget.unitPrice()).isEqualByComparingTo("12.50");
    assertThat(updatedTarget.lineTotal()).isEqualByComparingTo("87.50");
    assertThat(untouched.quantity()).isEqualByComparingTo(other.quantity());
    assertThat(untouched.uom()).isEqualTo(other.uom());
    assertThat(updated.lineCount()).isEqualTo(2);
  }

  @Test
  void correctingOrderLineUpdatesOnlyThatLine() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    DraftPreparationResult prepared = prepareOrder(tenantId, List.of("SKU-OA", "SKU-OB"));
    List<DraftOrderLineView> before = draftReviewService.orderDetail(prepared.draftId()).lines();
    DraftOrderLineView target = before.get(0);
    DraftOrderLineView other = before.get(1);

    DraftOrderDetail updated = draftReviewService.correctOrderLine(prepared.draftId(), target.lineId(),
        new DraftLineCorrectionRequest(new BigDecimal("4"), "EA", null, null, null, "operator fixed qty", UUID.randomUUID()));

    DraftOrderLineView updatedTarget = updated.lines().stream().filter(l -> l.lineId().equals(target.lineId())).findFirst().orElseThrow();
    DraftOrderLineView untouched = updated.lines().stream().filter(l -> l.lineId().equals(other.lineId())).findFirst().orElseThrow();
    assertThat(updatedTarget.quantity()).isEqualByComparingTo("4");
    assertThat(untouched.quantity()).isEqualByComparingTo(other.quantity());
    assertThat(updated.lineCount()).isEqualTo(2);
  }

  @Test
  void invalidQuantityFailsClosed() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    DraftPreparationResult prepared = prepareQuote(tenantId, List.of("SKU-IQ"));
    UUID lineId = draftReviewService.quoteDetail(prepared.draftId()).lines().get(0).lineId();

    assertThatThrownBy(() -> draftReviewService.correctQuoteLine(prepared.draftId(), lineId, new DraftLineCorrectionRequest(BigDecimal.ZERO, null, null, null, null, null, UUID.randomUUID())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Quantity must be positive");
  }

  @Test
  void overlongTextFailsClosed() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    DraftPreparationResult prepared = prepareQuote(tenantId, List.of("SKU-OL"));
    UUID lineId = draftReviewService.quoteDetail(prepared.draftId()).lines().get(0).lineId();
    String tooLong = "x".repeat(513);

    assertThatThrownBy(() -> draftReviewService.correctQuoteLine(prepared.draftId(), lineId, new DraftLineCorrectionRequest(null, null, tooLong, null, null, null, UUID.randomUUID())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Description must be at most");
  }

  @Test
  void lockedDraftCannotBeCorrected() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    DraftPreparationResult prepared = prepareQuote(tenantId, List.of("SKU-LOCK"));
    UUID lineId = draftReviewService.quoteDetail(prepared.draftId()).lines().get(0).lineId();
    draftReviewService.markQuoteReady(prepared.draftId(), new ReviewActionRequest(UUID.randomUUID(), "ready"));
    // Move to a terminal/locked status outside this service's lifecycle.
    quoteApproveInternal(prepared.draftId(), tenantId);

    assertThatThrownBy(() -> draftReviewService.correctQuoteLine(prepared.draftId(), lineId, new DraftLineCorrectionRequest(new BigDecimal("2"), null, null, null, null, null, UUID.randomUUID())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("locked");
  }

  @Test
  void markReadyChangesStatusOnlyThroughAllowedTransition() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    DraftPreparationResult prepared = prepareQuote(tenantId, List.of("SKU-MR"));

    DraftQuoteDetail ready = draftReviewService.markQuoteReady(prepared.draftId(), new ReviewActionRequest(UUID.randomUUID(), "ready for internal approval"));
    assertThat(ready.status()).isEqualTo("WAITING_APPROVAL");

    // idempotent repeat stays in the same status
    DraftQuoteDetail again = draftReviewService.markQuoteReady(prepared.draftId(), new ReviewActionRequest(UUID.randomUUID(), "again"));
    assertThat(again.status()).isEqualTo("WAITING_APPROVAL");

    // terminal -> mark-ready fails closed
    quoteApproveInternal(prepared.draftId(), tenantId);
    assertThatThrownBy(() -> draftReviewService.markQuoteReady(prepared.draftId(), new ReviewActionRequest(UUID.randomUUID(), "no")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be marked ready");
  }

  @Test
  void repeatedSameCorrectionIsSafeAndDoesNotDuplicateLines() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    DraftPreparationResult prepared = prepareQuote(tenantId, List.of("SKU-DUP"));
    UUID lineId = draftReviewService.quoteDetail(prepared.draftId()).lines().get(0).lineId();
    DraftLineCorrectionRequest request = new DraftLineCorrectionRequest(new BigDecimal("3"), "EA", null, null, null, "same", UUID.randomUUID());

    draftReviewService.correctQuoteLine(prepared.draftId(), lineId, request);
    DraftQuoteDetail afterSecond = draftReviewService.correctQuoteLine(prepared.draftId(), lineId, request);

    assertThat(afterSecond.lineCount()).isEqualTo(1);
    assertThat(quoteLines.findByTenantIdAndDraftQuoteId(tenantId, prepared.draftId())).hasSize(1);
    assertThat(afterSecond.lines().get(0).quantity()).isEqualByComparingTo("3");
  }

  @Test
  void correctionAndMarkReadyEmitAudit() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    DraftPreparationResult prepared = prepareQuote(tenantId, List.of("SKU-AUD"));
    UUID lineId = draftReviewService.quoteDetail(prepared.draftId()).lines().get(0).lineId();

    draftReviewService.correctQuoteLine(prepared.draftId(), lineId, new DraftLineCorrectionRequest(new BigDecimal("2"), null, null, null, null, "reason", UUID.randomUUID()));
    draftReviewService.markQuoteReady(prepared.draftId(), new ReviewActionRequest(UUID.randomUUID(), "ready"));

    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenantId))
        .extracting("action")
        .contains("DRAFT_QUOTE_LINE_CORRECTED", "DRAFT_QUOTE_MARKED_READY");
    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenantId))
        .extracting("action")
        .noneMatch(action -> ((String) action).contains("CONNECTOR") || ((String) action).contains("ERP") || ((String) action).contains("OUTBOX"));
  }

  @Test
  void detailDtoDoesNotExposeRawAiPayload() throws Exception {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    DraftPreparationResult prepared = prepareQuote(tenantId, List.of("SKU-SAFE"));

    String json = objectMapper.writeValueAsString(draftReviewService.quoteDetail(prepared.draftId()));

    assertThat(json).doesNotContain("result_json").doesNotContain("resultJson").doesNotContain("rawText").doesNotContain("messageText");
    assertThat(json).contains("\"externalExecution\":\"DISABLED\"");
  }

  @Test
  void correctionDoesNotMutateMasterData() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    DraftPreparationResult prepared = prepareQuote(tenantId, List.of("SKU-MD"));
    UUID lineId = draftReviewService.quoteDetail(prepared.draftId()).lines().get(0).lineId();
    long productsBefore = products.count();
    long customersBefore = customers.count();
    long pricesBefore = prices.count();
    long inventoryBefore = inventory.count();
    long discountsBefore = discounts.count();
    long marginsBefore = margins.count();

    draftReviewService.correctQuoteLine(prepared.draftId(), lineId, new DraftLineCorrectionRequest(new BigDecimal("9"), "EA", null, new BigDecimal("5"), null, "reason", UUID.randomUUID()));

    assertThat(products.count()).isEqualTo(productsBefore);
    assertThat(customers.count()).isEqualTo(customersBefore);
    assertThat(prices.count()).isEqualTo(pricesBefore);
    assertThat(inventory.count()).isEqualTo(inventoryBefore);
    assertThat(discounts.count()).isEqualTo(discountsBefore);
    assertThat(margins.count()).isEqualTo(marginsBefore);
  }

  // --- helpers ---

  private DraftPreparationResult prepareQuote(UUID tenantId, List<String> skus) {
    return prepareDraft(tenantId, skus, "RFQ");
  }

  private DraftPreparationResult prepareOrder(UUID tenantId, List<String> skus) {
    return prepareDraft(tenantId, skus, "PURCHASE_ORDER");
  }

  private DraftPreparationResult prepareDraft(UUID tenantId, List<String> skus, String intent) {
    Location location = location(tenantId, "WS-" + skus.get(0));
    customer(tenantId, "ACME-" + skus.get(0), "Acme " + skus.get(0), location.getId());
    ExtractionResult extraction = extractionResults.save(new ExtractionResult(tenantId, UUID.randomUUID(), "CHANNEL_MESSAGE", UUID.randomUUID(), intent, "message", new BigDecimal("0.95"), "{}", "ready_for_validation", NOW));
    fields.save(new ExtractedField(tenantId, extraction.getId(), "customer_hint", "Acme " + skus.get(0), "Acme " + skus.get(0), "customer_hint", new BigDecimal("0.95"), null, NOW));
    int lineNumber = 1;
    for (String sku : skus) {
      Product product = product(tenantId, sku, sku + " Filter", "10");
      prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
      inventory.save(new InventorySnapshot(tenantId, product.getId(), location.getId(), new BigDecimal("12"), new BigDecimal("12"), BigDecimal.ZERO, NOW, "TEST", null, NOW));
      lines.save(new ExtractedLineItem(tenantId, extraction.getId(), lineNumber++, sku, sku + " Filter", "2", new BigDecimal("2"), "EA", "EA", new BigDecimal("0.95"), null, NOW));
    }
    validationRunService.run(extraction.getId(), "FULL");
    UUID reviewCaseId = reviewService.createForExtractionResult(extraction.getId()).reviewCase().id();
    reviewService.approveForDraft(reviewCaseId, UUID.randomUUID());
    return draftPreparationService.prepareDraft(reviewCaseId, UUID.randomUUID());
  }

  private void quoteApproveInternal(UUID draftQuoteId, UUID tenantId) {
    // Uses the existing DraftQuoteService.approve path to reach a terminal/locked status (APPROVED_INTERNAL).
    draftQuoteService.approve(draftQuoteId);
  }

  @Autowired private DraftQuoteService draftQuoteService;

  private CustomerAccount customer(UUID tenantId, String accountCode, String name, UUID defaultLocationId) {
    return customers.save(new CustomerAccount(tenantId, null, accountCode, name, name, null, "ACTIVE", "USD", defaultLocationId, NOW));
  }

  private Product product(UUID tenantId, String sku, String name, String cost) {
    return products.save(new Product(tenantId, sku, name, name, "Filters", "Brand", "Maker", "EA", "ACTIVE", new BigDecimal(cost), "USD", NOW));
  }

  private Location location(UUID tenantId, String code) {
    return locations.save(new Location(tenantId, code, code, "WAREHOUSE", null, "Almaty", "KZ", true, NOW));
  }
}
