package com.orderpilot.application.services.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.Stage6Dtos.DraftPreparationResult;
import com.orderpilot.api.dto.Stage6Dtos.ReviewCaseDetail;
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
import com.orderpilot.domain.pricing.PriceRule;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-09A Draft Quote/Order Preparation Foundation.
 * Maps the prompt's "AI validation handoff" to the existing validation-backed review case (ExceptionCase),
 * and "DRAFT_PREPARATION_READY" to the existing draft-preparation readiness gate.
 */
@SpringBootTest
@ActiveProfiles("test")
class DraftPreparationFoundationStage9ATest {
  private static final Instant NOW = Instant.parse("2026-06-07T00:00:00Z");

  @Autowired private ValidationRunService validationRunService;
  @Autowired private ValidationReviewService reviewService;
  @Autowired private DraftCommandPreparationService draftPreparationService;
  @Autowired private ExtractionResultRepository extractionResults;
  @Autowired private ExtractedFieldRepository fields;
  @Autowired private ExtractedLineItemRepository lines;
  @Autowired private CustomerAccountRepository customers;
  @Autowired private ProductRepository products;
  @Autowired private LocationRepository locations;
  @Autowired private InventorySnapshotRepository inventory;
  @Autowired private PriceRuleRepository prices;
  @Autowired private DraftQuoteRepository draftQuotes;
  @Autowired private DraftOrderRepository draftOrders;
  @Autowired private AuditEventRepository auditEvents;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void createsDraftQuoteFromApprovedRfqHandoff() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID reviewCaseId = readyReviewCase(tenantId, "SKU-RFQ", "RFQ");
    reviewService.approveForDraft(reviewCaseId, UUID.randomUUID());

    DraftPreparationResult result = draftPreparationService.prepareDraft(reviewCaseId, UUID.randomUUID());

    assertThat(result.draftType()).isEqualTo("QUOTE");
    assertThat(result.created()).isTrue();
    assertThat(result.alreadyExisted()).isFalse();
    assertThat(result.sourceHandoffId()).isEqualTo(reviewCaseId);
    assertThat(result.externalExecution()).isEqualTo("DISABLED");
    assertThat(result.nextAction()).isEqualTo("OPEN_OPERATOR_WORKSPACE");
    assertThat(draftQuotes.findByIdAndTenantId(result.draftId(), tenantId)).isPresent();
    assertThat(draftQuotes.findByIdAndTenantId(result.draftId(), tenantId).orElseThrow().getSourceExceptionCaseId()).isEqualTo(reviewCaseId);
  }

  @Test
  void createsDraftOrderFromApprovedPurchaseOrderHandoff() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID reviewCaseId = readyReviewCase(tenantId, "SKU-PO", "PURCHASE_ORDER");
    reviewService.approveForDraft(reviewCaseId, UUID.randomUUID());

    DraftPreparationResult result = draftPreparationService.prepareDraft(reviewCaseId, UUID.randomUUID());

    assertThat(result.draftType()).isEqualTo("ORDER");
    assertThat(result.created()).isTrue();
    assertThat(draftOrders.findByIdAndTenantId(result.draftId(), tenantId)).isPresent();
    assertThat(draftOrders.findByIdAndTenantId(result.draftId(), tenantId).orElseThrow().getSourceExceptionCaseId()).isEqualTo(reviewCaseId);
  }

  @Test
  void failsClosedForBlockedHandoff() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product product = product(tenantId, "SKU-BLOCK", "Block Filter", "10");
    prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    // PALLET uom triggers a hard INVALID_UOM block that keeps the handoff out of DRAFT_PREPARATION_READY.
    ExtractionResult extraction = extraction(tenantId, "SKU-BLOCK", "Block Filter", "2", "PALLET", "RFQ");
    validationRunService.run(extraction.getId(), "FULL");
    UUID reviewCaseId = reviewService.createForExtractionResult(extraction.getId()).reviewCase().id();

    assertThatThrownBy(() -> draftPreparationService.prepareDraft(reviewCaseId, UUID.randomUUID()))
        .isInstanceOf(DraftPreparationBlockedException.class);
    assertThat(draftQuotes.findByTenantIdOrderByCreatedAtDesc(tenantId)).isEmpty();
    assertThat(draftOrders.findByTenantIdOrderByCreatedAtDesc(tenantId)).isEmpty();
  }

  @Test
  void failsClosedForUnsupportedIntent() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID reviewCaseId = readyReviewCase(tenantId, "SKU-AVAIL", "AVAILABILITY_REQUEST");
    reviewService.approveForDraft(reviewCaseId, UUID.randomUUID());

    assertThatThrownBy(() -> draftPreparationService.prepareDraft(reviewCaseId, UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported document intent");
    assertThat(draftQuotes.findByTenantIdOrderByCreatedAtDesc(tenantId)).isEmpty();
    assertThat(draftOrders.findByTenantIdOrderByCreatedAtDesc(tenantId)).isEmpty();
  }

  @Test
  void repeatedCallIsIdempotentAndDoesNotCreateDuplicateDraft() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID reviewCaseId = readyReviewCase(tenantId, "SKU-IDEMP", "RFQ");
    reviewService.approveForDraft(reviewCaseId, UUID.randomUUID());

    DraftPreparationResult first = draftPreparationService.prepareDraft(reviewCaseId, UUID.randomUUID());
    DraftPreparationResult second = draftPreparationService.prepareDraft(reviewCaseId, UUID.randomUUID());

    assertThat(first.created()).isTrue();
    assertThat(second.created()).isFalse();
    assertThat(second.alreadyExisted()).isTrue();
    assertThat(second.draftId()).isEqualTo(first.draftId());
    assertThat(draftQuotes.findByTenantIdOrderByCreatedAtDesc(tenantId)).hasSize(1);
  }

  @Test
  void preparationDoesNotMutateMasterData() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID reviewCaseId = readyReviewCase(tenantId, "SKU-MASTER", "RFQ");
    reviewService.approveForDraft(reviewCaseId, UUID.randomUUID());
    long productsBefore = products.count();
    long customersBefore = customers.count();
    long pricesBefore = prices.count();
    long inventoryBefore = inventory.count();

    draftPreparationService.prepareDraft(reviewCaseId, UUID.randomUUID());

    assertThat(products.count()).isEqualTo(productsBefore);
    assertThat(customers.count()).isEqualTo(customersBefore);
    assertThat(prices.count()).isEqualTo(pricesBefore);
    assertThat(inventory.count()).isEqualTo(inventoryBefore);
  }

  @Test
  void tenantCannotPrepareAnotherTenantHandoff() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    UUID reviewCaseId = readyReviewCase(tenantA, "SKU-TENANT", "RFQ");
    reviewService.approveForDraft(reviewCaseId, UUID.randomUUID());

    TenantContext.setTenantId(UUID.randomUUID());
    assertThatThrownBy(() -> draftPreparationService.prepareDraft(reviewCaseId, UUID.randomUUID()))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void preparationEmitsAuditAndNoExternalConnectorWrite() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID reviewCaseId = readyReviewCase(tenantId, "SKU-AUDIT", "RFQ");
    reviewService.approveForDraft(reviewCaseId, UUID.randomUUID());

    DraftPreparationResult result = draftPreparationService.prepareDraft(reviewCaseId, UUID.randomUUID());

    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenantId))
        .extracting("action")
        .contains("DRAFT_PREPARATION_SUCCEEDED");
    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenantId))
        .extracting("action")
        .noneMatch(action -> ((String) action).contains("CONNECTOR") || ((String) action).contains("ERP") || ((String) action).contains("EXTERNAL_EXECUTION"));
    // Bounded status token, never raw AI JSON.
    assertThat(result.status()).doesNotContain("{").doesNotContain("result_json");
  }

  private UUID readyReviewCase(UUID tenantId, String sku, String intent) {
    Product product = product(tenantId, sku, sku + " Filter", "10");
    Location location = location(tenantId, sku);
    customer(tenantId, "ACME-" + sku, "Acme " + sku, location.getId());
    inventory.save(new InventorySnapshot(tenantId, product.getId(), location.getId(), new BigDecimal("12"), new BigDecimal("12"), BigDecimal.ZERO, NOW, "TEST", null, NOW));
    prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    ExtractionResult extraction = extraction(tenantId, sku, sku + " Filter", "2", "EA", intent);
    validationRunService.run(extraction.getId(), "FULL");
    return reviewService.createForExtractionResult(extraction.getId()).reviewCase().id();
  }

  private ExtractionResult extraction(UUID tenantId, String sku, String description, String quantity, String uom, String intent) {
    ExtractionResult result = extractionResults.save(new ExtractionResult(tenantId, UUID.randomUUID(), "CHANNEL_MESSAGE", UUID.randomUUID(), intent, "message", new BigDecimal("0.95"), "{}", "ready_for_validation", NOW));
    fields.save(new ExtractedField(tenantId, result.getId(), "customer_hint", "Acme " + sku, "Acme " + sku, "customer_hint", new BigDecimal("0.95"), null, NOW));
    lines.save(new ExtractedLineItem(tenantId, result.getId(), 1, sku, description, quantity, new BigDecimal(quantity), uom, uom, new BigDecimal("0.95"), null, NOW));
    return result;
  }

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
