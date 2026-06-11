package com.orderpilot.application.services.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftResult;
import com.orderpilot.application.services.workspace.DraftPreparationBlockedException;
import com.orderpilot.common.errors.NotFoundException;
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
import com.orderpilot.domain.validation.ValidationRun;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuoteLine;
import com.orderpilot.domain.workspace.DraftQuoteLineRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
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
 * OP-CAP-15A — Validation Review → Draft Quote / Draft Order bridge.
 *
 * <p>Proves an operator can create an internal draft from a tenant-scoped validation run review,
 * that unresolved blocking issues fail closed, that drafts use validated/normalized (not raw) values
 * and keep source traceability, that cross-tenant creation is impossible, that replays are idempotent,
 * that the action is audited, and that no master data is mutated.
 */
@SpringBootTest
@ActiveProfiles("test")
class ValidationReviewDraftBridgeStage15ATest {
  private static final Instant NOW = Instant.parse("2026-06-09T00:00:00Z");

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
  @Autowired private DraftQuoteRepository draftQuotes;
  @Autowired private DraftQuoteLineRepository draftQuoteLines;
  @Autowired private DraftOrderRepository draftOrders;
  @Autowired private AuditEventRepository auditEvents;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void createsDraftQuoteFromCleanValidationRunWithSourceTraceability() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID runId = cleanRun(tenantId, "SKU-Q", new BigDecimal("2"), new BigDecimal("2"));

    ValidationReviewDraftResult result = draftBridge.createDraftQuote(runId, UUID.randomUUID());

    assertThat(result.draftType()).isEqualTo("QUOTE");
    assertThat(result.created()).isTrue();
    assertThat(result.alreadyExisted()).isFalse();
    assertThat(result.sourceReviewId()).isEqualTo(runId);
    assertThat(result.createdLineCount()).isEqualTo(1);
    assertThat(result.unresolvedBlockingIssueCount()).isZero();
    assertThat(result.externalExecution()).isEqualTo("DISABLED");
    assertThat(result.nextRoute()).isEqualTo("/workspace/draft-quotes/" + result.draftId());
    // Source traceability back to the validation run.
    assertThat(draftQuotes.findByIdAndTenantId(result.draftId(), tenantId).orElseThrow().getSourceValidationRunId()).isEqualTo(runId);
  }

  @Test
  void createsDraftOrderFromCleanValidationRun() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID runId = cleanRun(tenantId, "SKU-O", new BigDecimal("2"), new BigDecimal("2"));

    ValidationReviewDraftResult result = draftBridge.createDraftOrder(runId, UUID.randomUUID());

    assertThat(result.draftType()).isEqualTo("ORDER");
    assertThat(result.created()).isTrue();
    assertThat(draftOrders.findByIdAndTenantId(result.draftId(), tenantId).orElseThrow().getSourceValidationRunId()).isEqualTo(runId);
  }

  @Test
  void draftUsesValidatedNormalizedValuesNotRawAiOutput() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    // raw quantity "2" but the validated/normalized quantity is 5 — the draft must use the validated value.
    UUID runId = cleanRun(tenantId, "SKU-NORM", new BigDecimal("2"), new BigDecimal("5"));

    ValidationReviewDraftResult result = draftBridge.createDraftQuote(runId, UUID.randomUUID());

    List<DraftQuoteLine> draftLines = draftQuoteLines.findByTenantIdAndDraftQuoteId(tenantId, result.draftId());
    assertThat(draftLines).hasSize(1);
    assertThat(draftLines.get(0).getQuantity()).isEqualByComparingTo("5");
  }

  @Test
  void unresolvedBlockingIssueFailsClosed() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    // Quantity 0 raises a hard INVALID_QUANTITY validation issue that blocks draft preparation.
    UUID runId = run(tenantId, "SKU-BLK", new BigDecimal("0"), new BigDecimal("0"), "EA", "RFQ");

    assertThatThrownBy(() -> draftBridge.createDraftQuote(runId, UUID.randomUUID()))
        .isInstanceOf(DraftPreparationBlockedException.class);
    assertThat(draftQuotes.findByTenantIdOrderByCreatedAtDesc(tenantId)).isEmpty();
    assertThat(draftOrders.findByTenantIdOrderByCreatedAtDesc(tenantId)).isEmpty();
  }

  @Test
  void crossTenantCannotCreateDraft() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    UUID runId = cleanRun(tenantA, "SKU-X", new BigDecimal("2"), new BigDecimal("2"));

    TenantContext.setTenantId(UUID.randomUUID());
    assertThatThrownBy(() -> draftBridge.createDraftQuote(runId, UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("validation_run_not_found");
    TenantContext.setTenantId(tenantA);
    assertThat(draftQuotes.findByTenantIdOrderByCreatedAtDesc(tenantA)).isEmpty();
  }

  @Test
  void idempotentReplayReturnsSameDraftAndDoesNotDuplicate() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID runId = cleanRun(tenantId, "SKU-IDEMP", new BigDecimal("2"), new BigDecimal("2"));

    ValidationReviewDraftResult first = draftBridge.createDraftQuote(runId, UUID.randomUUID());
    ValidationReviewDraftResult second = draftBridge.createDraftQuote(runId, UUID.randomUUID());

    assertThat(first.created()).isTrue();
    assertThat(second.created()).isFalse();
    assertThat(second.alreadyExisted()).isTrue();
    assertThat(second.draftId()).isEqualTo(first.draftId());
    assertThat(draftQuotes.findByTenantIdOrderByCreatedAtDesc(tenantId)).hasSize(1);
  }

  @Test
  void emitsAuditAndDoesNotMutateMasterData() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID runId = cleanRun(tenantId, "SKU-AUD", new BigDecimal("2"), new BigDecimal("2"));
    long productsBefore = products.count();
    long customersBefore = customers.count();
    long pricesBefore = prices.count();
    long inventoryBefore = inventory.count();

    draftBridge.createDraftQuote(runId, UUID.randomUUID());

    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenantId))
        .extracting("action")
        .contains("DRAFT_PREPARATION_SUCCEEDED");
    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenantId))
        .extracting("action")
        .noneMatch(action -> ((String) action).contains("CONNECTOR") || ((String) action).contains("ERP") || ((String) action).contains("EXTERNAL_EXECUTION"));
    assertThat(products.count()).isEqualTo(productsBefore);
    assertThat(customers.count()).isEqualTo(customersBefore);
    assertThat(prices.count()).isEqualTo(pricesBefore);
    assertThat(inventory.count()).isEqualTo(inventoryBefore);
  }

  @Test
  void runWithNoLineItemsFailsClosed() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ExtractionResult extraction = extractionResults.save(new ExtractionResult(tenantId, UUID.randomUUID(), "CHANNEL_MESSAGE", UUID.randomUUID(), "RFQ", "message", new BigDecimal("0.95"), "{}", "ready_for_validation", NOW));
    ValidationRun run = validationRunService.run(extraction.getId(), "FULL");

    assertThatThrownBy(() -> draftBridge.createDraftQuote(run.getId(), UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no_valid_line_items");
  }

  // --- helpers -----------------------------------------------------------------------------------

  private UUID cleanRun(UUID tenantId, String sku, BigDecimal rawQty, BigDecimal normalizedQty) {
    Product product = products.save(new Product(tenantId, sku, sku + " Filter", sku + " Filter", "Filters", "Brand", "Maker", "EA", "ACTIVE", new BigDecimal("10"), "USD", NOW));
    Location location = locations.save(new Location(tenantId, sku, sku, "WAREHOUSE", null, "Almaty", "KZ", true, NOW));
    customers.save(new CustomerAccount(tenantId, null, "ACME-" + sku, "Acme " + sku, "Acme " + sku, null, "ACTIVE", "USD", location.getId(), NOW));
    inventory.save(new InventorySnapshot(tenantId, product.getId(), location.getId(), new BigDecimal("50"), new BigDecimal("50"), BigDecimal.ZERO, NOW, "TEST", null, NOW));
    prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    return run(tenantId, sku, rawQty, normalizedQty, "EA", "RFQ");
  }

  private UUID run(UUID tenantId, String sku, BigDecimal rawQty, BigDecimal normalizedQty, String uom, String intent) {
    ExtractionResult extraction = extractionResults.save(new ExtractionResult(tenantId, UUID.randomUUID(), "CHANNEL_MESSAGE", UUID.randomUUID(), intent, "message", new BigDecimal("0.95"), "{}", "ready_for_validation", NOW));
    fields.save(new ExtractedField(tenantId, extraction.getId(), "customer_hint", "Acme " + sku, "Acme " + sku, "customer_hint", new BigDecimal("0.95"), null, NOW));
    lines.save(new ExtractedLineItem(tenantId, extraction.getId(), 1, sku, sku + " Filter", rawQty.toPlainString(), normalizedQty, uom, uom, new BigDecimal("0.95"), null, NOW));
    return validationRunService.run(extraction.getId(), "FULL").getId();
  }
}
