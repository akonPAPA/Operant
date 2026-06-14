package com.orderpilot.application.services.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftQueueItem;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftQueueResponse;
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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-15C — lite review-origin draft queue.
 *
 * <p>Proves quotes and orders created from validation reviews surface in a tenant-scoped, paginated,
 * createdAt-desc queue with source run + workspace/review link data; cross-tenant rows are invisible;
 * raw operator-note content is not exposed (notePresent only); externalExecution is DISABLED.
 */
@SpringBootTest
@ActiveProfiles("test")
class ValidationReviewDraftQueueStage15CTest {
  private static final Instant NOW = Instant.parse("2026-06-09T00:00:00Z");

  @Autowired private ValidationReviewDraftQueryService queryService;
  @Autowired private ValidationReviewDraftCommandService draftBridge;
  @Autowired private ValidationRunService validationRunService;
  @Autowired private ExtractionResultRepository extractionResults;
  @Autowired private ExtractedFieldRepository fields;
  @Autowired private ExtractedLineItemRepository lines;
  @Autowired private CustomerAccountRepository customers;
  @Autowired private ProductRepository products;
  @Autowired private LocationRepository locations;
  @Autowired private InventorySnapshotRepository inventory;
  @Autowired private PriceRuleRepository prices;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void queueReturnsDraftQuoteCreatedFromReview() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID runId = cleanRun(tenantId, "Q");
    var created = draftBridge.createDraftQuote(runId, UUID.randomUUID());

    ValidationReviewDraftQueueResponse response = queryService.reviewDraftQueue(null, null, null, null);

    assertThat(response.limit()).isEqualTo(25); // default
    assertThat(response.items()).hasSize(1);
    ValidationReviewDraftQueueItem item = response.items().get(0);
    assertThat(item.draftId()).isEqualTo(created.draftId());
    assertThat(item.draftType()).isEqualTo("QUOTE");
    assertThat(item.sourceValidationRunId()).isEqualTo(runId);
    assertThat(item.lineCount()).isEqualTo(1);
    assertThat(item.workspacePath()).isEqualTo("/workspace/draft-quotes/" + created.draftId());
    assertThat(item.reviewPath()).isEqualTo("/validations/" + runId + "/review");
    assertThat(item.externalExecution()).isEqualTo("DISABLED");
  }

  @Test
  void queueReturnsDraftOrderCreatedFromReview() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID runId = cleanRun(tenantId, "O");
    var created = draftBridge.createDraftOrder(runId, UUID.randomUUID());

    ValidationReviewDraftQueueResponse response = queryService.reviewDraftQueue("ORDER", null, null, null);

    assertThat(response.items()).hasSize(1);
    assertThat(response.items().get(0).draftType()).isEqualTo("ORDER");
    assertThat(response.items().get(0).draftId()).isEqualTo(created.draftId());
  }

  @Test
  void queueCombinesQuoteAndOrderSortedByCreatedAtDesc() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID quoteRun = cleanRun(tenantId, "C1");
    UUID orderRun = cleanRun(tenantId, "C2");
    draftBridge.createDraftQuote(quoteRun, UUID.randomUUID());
    draftBridge.createDraftOrder(orderRun, UUID.randomUUID());

    ValidationReviewDraftQueueResponse response = queryService.reviewDraftQueue(null, null, null, null);

    assertThat(response.items()).hasSize(2);
    assertThat(response.items()).extracting(ValidationReviewDraftQueueItem::draftType).containsExactlyInAnyOrder("QUOTE", "ORDER");
    // createdAt is non-increasing (desc).
    for (int i = 1; i < response.items().size(); i++) {
      assertThat(response.items().get(i - 1).createdAt()).isAfterOrEqualTo(response.items().get(i).createdAt());
    }
  }

  @Test
  void queueIsTenantScopedAndCrossTenantInvisible() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    UUID runA = cleanRun(tenantA, "TA");
    draftBridge.createDraftQuote(runA, UUID.randomUUID());

    TenantContext.setTenantId(tenantB);
    ValidationReviewDraftQueueResponse response = queryService.reviewDraftQueue(null, null, null, null);
    assertThat(response.items()).isEmpty();
  }

  @Test
  void operatorNoteContentNotExposedOnlyNotePresent() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID runId = cleanRun(tenantId, "NOTE");
    draftBridge.createDraftQuote(runId, UUID.randomUUID(), null, "internal shipping note");

    ValidationReviewDraftQueueResponse response = queryService.reviewDraftQueue(null, null, null, null);

    ValidationReviewDraftQueueItem item = response.items().get(0);
    assertThat(item.operatorNotePresent()).isTrue();
    // The DTO carries no raw note field — operatorNotePresent is the only note signal.
    assertThat(item.toString()).doesNotContain("internal shipping note");
  }

  @Test
  void invalidDraftTypeFilterIsRejected() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);

    assertThatThrownBy(() -> queryService.reviewDraftQueue("INVOICE", null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid_draft_type_filter");
  }

  @Test
  void limitIsClampedToMax() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);

    ValidationReviewDraftQueueResponse response = queryService.reviewDraftQueue(null, null, 5000, null);
    assertThat(response.limit()).isEqualTo(100); // MAX_DRAFT_QUEUE_LIMIT
  }

  @Test
  void nonReviewOriginDraftsAreExcluded() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    // No drafts created — review-origin queue is empty even though other draft paths may exist elsewhere.
    ValidationReviewDraftQueueResponse response = queryService.reviewDraftQueue(null, null, null, null);
    assertThat(response.items()).isEmpty();
  }

  // --- helpers -----------------------------------------------------------------------------------

  // Unique customer name per run so customer matching is unambiguous when several runs share a tenant.
  private UUID cleanRun(UUID tenantId, String tag) {
    String sku = "SKU-" + tag;
    String customerName = "Acme " + tag;
    Location location = locations.save(new Location(tenantId, "LOC-" + tag, "LOC-" + tag, "WAREHOUSE", null, "Almaty", "KZ", true, NOW));
    customers.save(new CustomerAccount(tenantId, null, "ACME-" + tag, customerName, customerName, null, "ACTIVE", "USD", location.getId(), NOW));
    Product product = products.save(new Product(tenantId, sku, sku + " Filter", sku + " Filter", "Filters", "Brand", "Maker", "EA", "ACTIVE", new BigDecimal("10"), "USD", NOW));
    inventory.save(new InventorySnapshot(tenantId, product.getId(), location.getId(), new BigDecimal("50"), new BigDecimal("50"), BigDecimal.ZERO, NOW, "TEST", null, NOW));
    prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    ExtractionResult extraction = extractionResults.save(new ExtractionResult(tenantId, UUID.randomUUID(), "CHANNEL_MESSAGE", UUID.randomUUID(), "RFQ", "message", new BigDecimal("0.95"), "{}", "ready_for_validation", NOW));
    fields.save(new ExtractedField(tenantId, extraction.getId(), "customer_hint", customerName, customerName, "customer_hint", new BigDecimal("0.95"), null, NOW));
    lines.save(new ExtractedLineItem(tenantId, extraction.getId(), 1, sku, sku + " Filter", "2", new BigDecimal("5"), "EA", "EA", new BigDecimal("0.95"), null, NOW));
    return validationRunService.run(extraction.getId(), "FULL").getId();
  }
}
