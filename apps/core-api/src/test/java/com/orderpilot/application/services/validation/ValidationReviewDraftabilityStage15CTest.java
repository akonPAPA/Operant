package com.orderpilot.application.services.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftabilityResponse;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewLineDraftability;
import com.orderpilot.application.services.workspace.DraftPreparationBlockedException;
import com.orderpilot.common.errors.NotFoundException;
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
import com.orderpilot.domain.validation.ValidationIssue;
import com.orderpilot.domain.validation.ValidationIssueRepository;
import com.orderpilot.domain.workspace.DraftOrderRepository;
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
 * OP-CAP-15C — advisory per-line draftability hints.
 *
 * <p>Proves ready/blocked/warning line semantics, that existing drafts mark lines already drafted, that
 * a pure draftability read creates no draft and mutates no master data, that cross-tenant runs 404, and
 * that the create endpoint stays authoritative (still rejects a blocked selected line).
 */
@SpringBootTest
@ActiveProfiles("test")
class ValidationReviewDraftabilityStage15CTest {
  private static final Instant NOW = Instant.parse("2026-06-09T00:00:00Z");

  @Autowired private ValidationReviewDraftabilityService draftability;
  @Autowired private ValidationReviewDraftCommandService draftBridge;
  @Autowired private ValidationRunService validationRunService;
  @Autowired private ExtractionResultRepository extractionResults;
  @Autowired private ExtractedFieldRepository fields;
  @Autowired private ExtractedLineItemRepository lines;
  @Autowired private ValidationIssueRepository issues;
  @Autowired private CustomerAccountRepository customers;
  @Autowired private ProductRepository products;
  @Autowired private LocationRepository locations;
  @Autowired private InventorySnapshotRepository inventory;
  @Autowired private PriceRuleRepository prices;
  @Autowired private DraftQuoteRepository draftQuotes;
  @Autowired private DraftOrderRepository draftOrders;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void cleanRunMarksLineReadyAndCaseDraftable() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "OK");

    ValidationReviewDraftabilityResponse response = draftability.draftability(run.runId);

    assertThat(response.caseDraftable()).isTrue();
    assertThat(response.draftExists()).isFalse();
    assertThat(response.lineCount()).isEqualTo(1);
    assertThat(response.draftableLineCount()).isEqualTo(1);
    assertThat(response.externalExecution()).isEqualTo("DISABLED");
    ValidationReviewLineDraftability line = response.lines().get(0);
    assertThat(line.lineItemId()).isEqualTo(run.lineA);
    assertThat(line.draftable()).isTrue();
    assertThat(line.severity()).isEqualTo("OK");
    assertThat(line.reasons()).contains("LINE_READY");
    assertThat(line.normalizedQuantity()).isEqualByComparingTo("5");
  }

  @Test
  void unresolvedBlockingIssueMarksLineBlocked() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = blockedRun(tenantId, "BLK"); // qty 0 → INVALID_QUANTITY hard blocker

    ValidationReviewDraftabilityResponse response = draftability.draftability(run.runId);

    assertThat(response.caseDraftable()).isFalse();
    assertThat(response.overallSeverity()).isEqualTo("BLOCKED");
    ValidationReviewLineDraftability line = response.lines().get(0);
    assertThat(line.draftable()).isFalse();
    assertThat(line.severity()).isEqualTo("BLOCKED");
    assertThat(line.reasons()).contains("QUANTITY_NOT_NORMALIZED");
  }

  @Test
  void warningIssueMarksLineWarningButLineLocalDraftable() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "WARN");
    // A non-blocking WARNING issue on the line: line-local severity is WARNING, line stays draftable.
    issues.save(new ValidationIssue(tenantId, run.runId, run.extractionResultId, run.lineA, null, "PRICE_NEAR_FLOOR", "WARNING", "advisory warning", "{}", NOW));

    ValidationReviewDraftabilityResponse response = draftability.draftability(run.runId);

    ValidationReviewLineDraftability line = response.lines().get(0);
    assertThat(line.hasWarningIssue()).isTrue();
    assertThat(line.hasBlockingIssue()).isFalse();
    assertThat(line.severity()).isEqualTo("WARNING");
    assertThat(line.draftable()).isTrue();
    assertThat(line.reasons()).contains("WARNING_ISSUE_PRESENT");
  }

  @Test
  void missingNormalizedUomMarksLineBlocked() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Location location = seedCustomer(tenantId, "UOM");
    seedProduct(tenantId, "SKU-UOM", location);
    ExtractionResult extraction = extraction(tenantId, "RFQ");
    // normalizedUom null → UOM_NOT_NORMALIZED.
    ExtractedLineItem line = lines.save(new ExtractedLineItem(tenantId, extraction.getId(), 1, "SKU-UOM", "SKU-UOM Filter", "2", new BigDecimal("5"), "boxes", null, new BigDecimal("0.95"), null, NOW));
    UUID runId = validationRunService.run(extraction.getId(), "FULL").getId();

    ValidationReviewDraftabilityResponse response = draftability.draftability(runId);

    ValidationReviewLineDraftability hint = response.lines().stream().filter(l -> l.lineItemId().equals(line.getId())).findFirst().orElseThrow();
    assertThat(hint.severity()).isEqualTo("BLOCKED");
    assertThat(hint.draftable()).isFalse();
    assertThat(hint.reasons()).contains("UOM_NOT_NORMALIZED");
  }

  @Test
  void existingDraftMarksLinesAlreadyDrafted() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "EXIST");
    draftBridge.createDraftQuote(run.runId, UUID.randomUUID());

    ValidationReviewDraftabilityResponse response = draftability.draftability(run.runId);

    assertThat(response.draftExists()).isTrue();
    assertThat(response.existingDraftType()).isEqualTo("QUOTE");
    assertThat(response.existingWorkspacePath()).startsWith("/workspace/draft-quotes/");
    ValidationReviewLineDraftability line = response.lines().get(0);
    assertThat(line.alreadyDrafted()).isTrue();
    assertThat(line.reasons()).contains("LINE_ALREADY_INCLUDED_IN_EXISTING_DRAFT");
  }

  @Test
  void crossTenantDraftabilityReturnsNotFound() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    Run run = cleanRun(tenantA, "XS");

    TenantContext.setTenantId(UUID.randomUUID());
    assertThatThrownBy(() -> draftability.draftability(run.runId))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("validation_run_not_found");
  }

  @Test
  void draftabilityReadCreatesNoDraftAndNoMasterDataMutation() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "READ");
    long productsBefore = products.count();
    long customersBefore = customers.count();
    long pricesBefore = prices.count();

    draftability.draftability(run.runId);

    assertThat(draftQuotes.findByTenantIdOrderByCreatedAtDesc(tenantId)).isEmpty();
    assertThat(draftOrders.findByTenantIdOrderByCreatedAtDesc(tenantId)).isEmpty();
    assertThat(products.count()).isEqualTo(productsBefore);
    assertThat(customers.count()).isEqualTo(customersBefore);
    assertThat(prices.count()).isEqualTo(pricesBefore);
  }

  @Test
  void createStillRejectsBlockedSelectedLineEvenIfClientSendsIt() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = blockedRun(tenantId, "AUTH"); // hints would mark BLOCKED; create must fail closed regardless.

    assertThatThrownBy(() -> draftBridge.createDraftQuote(run.runId, UUID.randomUUID(), List.of(run.lineA), null))
        .isInstanceOf(DraftPreparationBlockedException.class);
    assertThat(draftQuotes.findByTenantIdOrderByCreatedAtDesc(tenantId)).isEmpty();
  }

  // --- helpers -----------------------------------------------------------------------------------

  private record Run(UUID runId, UUID extractionResultId, UUID lineA) {}

  private Run cleanRun(UUID tenantId, String tag) {
    String sku = "SKU-" + tag;
    Location location = seedCustomer(tenantId, tag);
    seedProduct(tenantId, sku, location);
    ExtractionResult extraction = extraction(tenantId, "RFQ");
    UUID lineA = saveLine(tenantId, extraction.getId(), sku, "2", new BigDecimal("5"));
    UUID runId = validationRunService.run(extraction.getId(), "FULL").getId();
    return new Run(runId, extraction.getId(), lineA);
  }

  private Run blockedRun(UUID tenantId, String tag) {
    String sku = "SKU-" + tag;
    Location location = seedCustomer(tenantId, tag);
    seedProduct(tenantId, sku, location);
    ExtractionResult extraction = extraction(tenantId, "RFQ");
    UUID lineA = saveLine(tenantId, extraction.getId(), sku, "0", new BigDecimal("0"));
    UUID runId = validationRunService.run(extraction.getId(), "FULL").getId();
    return new Run(runId, extraction.getId(), lineA);
  }

  private Location seedCustomer(UUID tenantId, String tag) {
    Location location = locations.save(new Location(tenantId, "LOC-" + tag, "LOC-" + tag, "WAREHOUSE", null, "Almaty", "KZ", true, NOW));
    customers.save(new CustomerAccount(tenantId, null, "ACME-" + tag, "Acme", "Acme", null, "ACTIVE", "USD", location.getId(), NOW));
    return location;
  }

  private void seedProduct(UUID tenantId, String sku, Location location) {
    Product product = products.save(new Product(tenantId, sku, sku + " Filter", sku + " Filter", "Filters", "Brand", "Maker", "EA", "ACTIVE", new BigDecimal("10"), "USD", NOW));
    inventory.save(new InventorySnapshot(tenantId, product.getId(), location.getId(), new BigDecimal("50"), new BigDecimal("50"), BigDecimal.ZERO, NOW, "TEST", null, NOW));
    prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
  }

  private ExtractionResult extraction(UUID tenantId, String intent) {
    ExtractionResult result = extractionResults.save(new ExtractionResult(tenantId, UUID.randomUUID(), "CHANNEL_MESSAGE", UUID.randomUUID(), intent, "message", new BigDecimal("0.95"), "{}", "ready_for_validation", NOW));
    fields.save(new ExtractedField(tenantId, result.getId(), "customer_hint", "Acme", "Acme", "customer_hint", new BigDecimal("0.95"), null, NOW));
    return result;
  }

  private UUID saveLine(UUID tenantId, UUID extractionResultId, String sku, String rawQty, BigDecimal normalizedQty) {
    ExtractedLineItem line = lines.save(new ExtractedLineItem(tenantId, extractionResultId, 1, sku, sku + " Filter", rawQty, normalizedQty, "EA", "EA", new BigDecimal("0.95"), null, NOW));
    return line.getId();
  }
}
