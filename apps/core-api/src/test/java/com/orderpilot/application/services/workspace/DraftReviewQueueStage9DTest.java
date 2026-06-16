package com.orderpilot.application.services.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.Stage6Dtos.DraftPreparationResult;
import com.orderpilot.api.dto.Stage6Dtos.DraftReviewSummary;
import com.orderpilot.api.dto.Stage6Dtos.ProductPickerItem;
import com.orderpilot.application.services.validation.ValidationRunService;
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
import com.orderpilot.domain.location.Location;
import com.orderpilot.domain.location.LocationRepository;
import com.orderpilot.domain.pricing.PriceRule;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductRepository;
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
 * OP-CAP-09D Draft Review Index / Queue + Product Picker.
 * Builds drafts through the real 09A path, then exercises the bounded tenant-scoped review queues,
 * status/limit handling, cross-tenant isolation, no-raw-payload contract, and the read-only product picker.
 */
@SpringBootTest
@ActiveProfiles("test")
class DraftReviewQueueStage9DTest {
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
  @Autowired private ObjectMapper objectMapper;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void quoteReviewQueueReturnsBoundedTenantScopedSummaries() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    DraftPreparationResult prepared = prepareDraft(tenantId, List.of("SKU-Q1", "SKU-Q2"), "RFQ");

    List<DraftReviewSummary> queue = draftReviewService.quoteReviewQueue(null, null, null, 25);

    assertThat(queue).hasSize(1);
    DraftReviewSummary summary = queue.get(0);
    assertThat(summary.draftId()).isEqualTo(prepared.draftId());
    assertThat(summary.draftType()).isEqualTo("QUOTE");
    assertThat(summary.sourceReviewCaseId()).isEqualTo(prepared.sourceHandoffId());
    assertThat(summary.lineCount()).isEqualTo(2);
    assertThat(summary.externalExecution()).isEqualTo("DISABLED");
    assertThat(summary.nextAction()).isEqualTo("REVIEW_LINES");
  }

  @Test
  void orderReviewQueueReturnsBoundedTenantScopedSummaries() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    DraftPreparationResult prepared = prepareDraft(tenantId, List.of("SKU-O1"), "PURCHASE_ORDER");

    List<DraftReviewSummary> queue = draftReviewService.orderReviewQueue(null, null, null, 25);

    assertThat(queue).hasSize(1);
    assertThat(queue.get(0).draftId()).isEqualTo(prepared.draftId());
    assertThat(queue.get(0).draftType()).isEqualTo("ORDER");
    assertThat(queue.get(0).lineCount()).isEqualTo(1);
  }

  @Test
  void queueFiltersByStatus() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    prepareDraft(tenantId, List.of("SKU-STAT"), "RFQ"); // status DRAFT after prepare

    assertThat(draftReviewService.quoteReviewQueue("DRAFT", null, null, 25)).hasSize(1);
    assertThat(draftReviewService.quoteReviewQueue("CANCELLED", null, null, 25)).isEmpty();
  }

  @Test
  void unknownStatusFilterFailsClosed() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);

    assertThatThrownBy(() -> draftReviewService.quoteReviewQueue("HACK", null, null, 25))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported draft status filter");
  }

  @Test
  void queueAppliesLimit() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    prepareDraft(tenantId, List.of("SKU-L1"), "RFQ");
    prepareDraft(tenantId, List.of("SKU-L2"), "RFQ");

    assertThat(draftReviewService.quoteReviewQueue(null, null, null, 1)).hasSize(1);
    // Oversized limit is clamped, not rejected, and never errors.
    assertThat(draftReviewService.quoteReviewQueue(null, null, null, 100000)).hasSize(2);
  }

  @Test
  void crossTenantDraftsAreNotReturned() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    prepareDraft(tenantA, List.of("SKU-XT"), "RFQ");

    TenantContext.setTenantId(UUID.randomUUID());
    assertThat(draftReviewService.quoteReviewQueue(null, null, null, 25)).isEmpty();
  }

  @Test
  void queueSummaryExposesNoRawPayloadOrLineArray() throws Exception {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    prepareDraft(tenantId, List.of("SKU-SAFE"), "RFQ");

    String json = objectMapper.writeValueAsString(draftReviewService.quoteReviewQueue(null, null, null, 25));

    assertThat(json).doesNotContain("result_json").doesNotContain("resultJson").doesNotContain("rawText").doesNotContain("messageText");
    assertThat(json).doesNotContain("\"lines\""); // summaries never carry full line arrays
    assertThat(json).contains("\"externalExecution\":\"DISABLED\"");
  }

  @Test
  void productSearchReturnsOnlyTenantActiveProducts() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    Product active = product(tenantA, "BRK-001", "Brake Pad", "ACTIVE");
    product(tenantA, "BRK-002", "Brake Disc Inactive", "INACTIVE");
    TenantContext.setTenantId(tenantB);
    product(tenantB, "BRK-003", "Brake Pad Other Tenant", "ACTIVE");

    TenantContext.setTenantId(tenantA);
    List<ProductPickerItem> results = draftReviewService.searchProducts("BRK", 25);

    assertThat(results).extracting(ProductPickerItem::productId).containsExactly(active.getId());
    assertThat(results).extracting(ProductPickerItem::status).allMatch("ACTIVE"::equals);
  }

  @Test
  void productSearchClampsLimitAndRequiresTerm() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    product(tenantId, "FLT-001", "Filter One", "ACTIVE");
    product(tenantId, "FLT-002", "Filter Two", "ACTIVE");

    assertThat(draftReviewService.searchProducts("FLT", 1)).hasSize(1);
    assertThat(draftReviewService.searchProducts("  ", 10)).isEmpty();
    assertThat(draftReviewService.searchProducts(null, 10)).isEmpty();
  }

  @Test
  void productPickerDtoExposesNoCostOrMarginFields() throws Exception {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    product(tenantId, "CST-001", "Picker Safety Part", "ACTIVE");

    String json = objectMapper.writeValueAsString(draftReviewService.searchProducts("CST", 10));

    assertThat(json).doesNotContainIgnoringCase("cost").doesNotContainIgnoringCase("margin").doesNotContainIgnoringCase("supplier");
  }

  @Test
  void productSearchDoesNotMutateMasterData() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    product(tenantId, "RO-001", "Read Only", "ACTIVE");
    long productsBefore = products.count();
    long customersBefore = customers.count();
    long pricesBefore = prices.count();
    long inventoryBefore = inventory.count();

    draftReviewService.searchProducts("RO", 10);

    assertThat(products.count()).isEqualTo(productsBefore);
    assertThat(customers.count()).isEqualTo(customersBefore);
    assertThat(prices.count()).isEqualTo(pricesBefore);
    assertThat(inventory.count()).isEqualTo(inventoryBefore);
  }

  // --- helpers (shared shape with the 09A/09B harness) ---

  private DraftPreparationResult prepareDraft(UUID tenantId, List<String> skus, String intent) {
    Location location = location(tenantId, "WS-" + skus.get(0));
    customer(tenantId, "ACME-" + skus.get(0), "Acme " + skus.get(0), location.getId());
    ExtractionResult extraction = extractionResults.save(new ExtractionResult(tenantId, UUID.randomUUID(), "CHANNEL_MESSAGE", UUID.randomUUID(), intent, "message", new BigDecimal("0.95"), "{}", "ready_for_validation", NOW));
    fields.save(new ExtractedField(tenantId, extraction.getId(), "customer_hint", "Acme " + skus.get(0), "Acme " + skus.get(0), "customer_hint", new BigDecimal("0.95"), null, NOW));
    int lineNumber = 1;
    for (String sku : skus) {
      Product product = product(tenantId, sku, sku + " Filter", "ACTIVE");
      prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
      inventory.save(new InventorySnapshot(tenantId, product.getId(), location.getId(), new BigDecimal("12"), new BigDecimal("12"), BigDecimal.ZERO, NOW, "TEST", null, NOW));
      lines.save(new ExtractedLineItem(tenantId, extraction.getId(), lineNumber++, sku, sku + " Filter", "2", new BigDecimal("2"), "EA", "EA", new BigDecimal("0.95"), null, NOW));
    }
    validationRunService.run(extraction.getId(), "FULL");
    UUID reviewCaseId = reviewService.createForExtractionResult(extraction.getId()).reviewCase().id();
    reviewService.approveForDraft(reviewCaseId, UUID.randomUUID());
    return draftPreparationService.prepareDraft(reviewCaseId, UUID.randomUUID());
  }

  private CustomerAccount customer(UUID tenantId, String accountCode, String name, UUID defaultLocationId) {
    return customers.save(new CustomerAccount(tenantId, null, accountCode, name, name, null, "ACTIVE", "USD", defaultLocationId, NOW));
  }

  private Product product(UUID tenantId, String sku, String name, String status) {
    return products.save(new Product(tenantId, sku, name, name, "Filters", "Brand", "Maker", "EA", status, new BigDecimal("10"), "USD", NOW));
  }

  private Location location(UUID tenantId, String code) {
    return locations.save(new Location(tenantId, code, code, "WAREHOUSE", null, "Almaty", "KZ", true, NOW));
  }
}
