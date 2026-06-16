package com.orderpilot.application.services.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftResult;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftStatus;
import com.orderpilot.application.services.workspace.DraftPreparationBlockedException;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEvent;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.extraction.ExtractedField;
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
import com.orderpilot.domain.workspace.DraftQuote;
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
 * OP-CAP-15B — draft visibility, selected-line subset, and operator note.
 *
 * <p>Proves the review surface can see whether a draft exists, an operator can create a draft from a
 * validated subset using normalized (not raw) values, invalid/foreign/empty selections fail closed,
 * a bounded operator note is persisted + audited, and idempotency/one-draft-per-review is preserved.
 */
@SpringBootTest
@ActiveProfiles("test")
class ValidationReviewDraftBridgeStage15BTest {
  private static final Instant NOW = Instant.parse("2026-06-09T00:00:00Z");

  @Autowired private ValidationReviewDraftCommandService draftBridge;
  @Autowired private ValidationRunService validationRunService;
  @Autowired private ExtractionResultRepository extractionResults;
  @Autowired private com.orderpilot.domain.extraction.ExtractedFieldRepository fields;
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
  void draftStatusReportsExistsFalseThenTrueAfterCreation() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "Q1");

    ValidationReviewDraftStatus before = draftBridge.draftStatus(run.runId);
    assertThat(before.exists()).isFalse();
    assertThat(before.draftType()).isNull();
    assertThat(before.externalExecution()).isEqualTo("DISABLED");

    ValidationReviewDraftResult created = draftBridge.createDraftQuote(run.runId, UUID.randomUUID());

    ValidationReviewDraftStatus after = draftBridge.draftStatus(run.runId);
    assertThat(after.exists()).isTrue();
    assertThat(after.draftType()).isEqualTo("QUOTE");
    assertThat(after.draftId()).isEqualTo(created.draftId());
    assertThat(after.workspacePath()).isEqualTo("/workspace/draft-quotes/" + created.draftId());
    assertThat(after.lineCount()).isEqualTo(1);
    assertThat(after.sourceValidationRunId()).isEqualTo(run.runId);
  }

  @Test
  void omittedSelectedLinesPreservesAllLinesBehavior() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRunTwoLines(tenantId, "ALL");

    ValidationReviewDraftResult result = draftBridge.createDraftQuote(run.runId, UUID.randomUUID(), null, null);

    assertThat(result.createdLineCount()).isEqualTo(2);
  }

  @Test
  void selectedSubsetCreatesDraftWithOnlySelectedNormalizedLines() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRunTwoLines(tenantId, "SEL");
    // line A: raw "2" / normalized 5 ; line B: normalized 7. Select only line A.

    ValidationReviewDraftResult result = draftBridge.createDraftQuote(run.runId, UUID.randomUUID(), List.of(run.lineA), null);

    assertThat(result.createdLineCount()).isEqualTo(1);
    List<DraftQuoteLine> draftLines = draftQuoteLines.findByTenantIdAndDraftQuoteId(tenantId, result.draftId());
    assertThat(draftLines).hasSize(1);
    // Validated/normalized value (5) is used, not the raw AI value (2).
    assertThat(draftLines.get(0).getQuantity()).isEqualByComparingTo("5");
  }

  @Test
  void selectedLineFromAnotherRunIsRejected() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run runOne = cleanRun(tenantId, "R1");
    Run runTwo = cleanRun(tenantId, "R2");

    assertThatThrownBy(() -> draftBridge.createDraftQuote(runOne.runId, UUID.randomUUID(), List.of(runTwo.lineA), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("selected_line_not_found");
    assertThat(draftQuotes.findByTenantIdOrderByCreatedAtDesc(tenantId)).isEmpty();
  }

  @Test
  void selectedLineFromAnotherTenantIsRejected() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantB);
    Run foreign = cleanRun(tenantB, "TB");
    TenantContext.setTenantId(tenantA);
    Run mine = cleanRun(tenantA, "TA");

    assertThatThrownBy(() -> draftBridge.createDraftQuote(mine.runId, UUID.randomUUID(), List.of(foreign.lineA), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("selected_line_not_found");
  }

  @Test
  void explicitEmptySelectedLinesIsRejected() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "EMPTY");

    assertThatThrownBy(() -> draftBridge.createDraftQuote(run.runId, UUID.randomUUID(), List.of(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("selected_lines_empty");
  }

  @Test
  void blockedSelectedLineReturns409AndNoDraft() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    // qty 0 raises a hard INVALID_QUANTITY issue → run-level readiness gate fails closed.
    Run run = blockedRun(tenantId, "BLK");

    assertThatThrownBy(() -> draftBridge.createDraftQuote(run.runId, UUID.randomUUID(), List.of(run.lineA), null))
        .isInstanceOf(DraftPreparationBlockedException.class);
    assertThat(draftQuotes.findByTenantIdOrderByCreatedAtDesc(tenantId)).isEmpty();
  }

  @Test
  void operatorNoteIsPersistedOnDraftAndAudited() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "NOTE");

    ValidationReviewDraftResult result = draftBridge.createDraftQuote(run.runId, UUID.randomUUID(), null, "  please prioritize shipping  ");

    DraftQuote quote = draftQuotes.findByIdAndTenantId(result.draftId(), tenantId).orElseThrow();
    assertThat(quote.getNotes()).isEqualTo("please prioritize shipping"); // trimmed
    // Audit records that a note was present (length only) — not the raw note content.
    List<AuditEvent> audit = auditEvents.findByTenantIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(tenantId, "DRAFT_QUOTE", result.draftId().toString());
    assertThat(audit).extracting(AuditEvent::getAction).contains("QUOTE_DRAFT_CREATED");
    assertThat(audit.stream().anyMatch(a -> a.getMetadata().contains("\"notePresent\":true"))).isTrue();
  }

  @Test
  void overlongOperatorNoteIsRejected() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "LONG");
    String tooLong = "x".repeat(1001);

    assertThatThrownBy(() -> draftBridge.createDraftQuote(run.runId, UUID.randomUUID(), null, tooLong))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("operator_note_too_long");
    assertThat(draftQuotes.findByTenantIdOrderByCreatedAtDesc(tenantId)).isEmpty();
  }

  @Test
  void idempotentReplayDoesNotDuplicate() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "IDEMP");

    ValidationReviewDraftResult first = draftBridge.createDraftQuote(run.runId, UUID.randomUUID(), List.of(run.lineA), "note one");
    ValidationReviewDraftResult second = draftBridge.createDraftQuote(run.runId, UUID.randomUUID(), List.of(run.lineA), "note two");

    assertThat(first.created()).isTrue();
    assertThat(second.created()).isFalse();
    assertThat(second.alreadyExisted()).isTrue();
    assertThat(second.draftId()).isEqualTo(first.draftId());
    assertThat(draftQuotes.findByTenantIdOrderByCreatedAtDesc(tenantId)).hasSize(1);
  }

  @Test
  void otherDraftTypeAfterExistingReturnsExistingDraft() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "BOTH");

    ValidationReviewDraftResult quote = draftBridge.createDraftQuote(run.runId, UUID.randomUUID());
    ValidationReviewDraftResult orderAttempt = draftBridge.createDraftOrder(run.runId, UUID.randomUUID());

    assertThat(orderAttempt.alreadyExisted()).isTrue();
    assertThat(orderAttempt.draftType()).isEqualTo("QUOTE");
    assertThat(orderAttempt.draftId()).isEqualTo(quote.draftId());
    assertThat(draftOrders.findByTenantIdOrderByCreatedAtDesc(tenantId)).isEmpty();
  }

  @Test
  void crossTenantDraftStatusReturnsNotFound() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    Run run = cleanRun(tenantA, "XS");

    TenantContext.setTenantId(UUID.randomUUID());
    assertThatThrownBy(() -> draftBridge.draftStatus(run.runId))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("validation_run_not_found");
  }

  // --- helpers -----------------------------------------------------------------------------------

  private record Run(UUID runId, UUID lineA, UUID lineB) {}

  private Run cleanRun(UUID tenantId, String tag) {
    String sku = "SKU-" + tag;
    Location location = seedCustomer(tenantId, tag);
    seedProduct(tenantId, sku, location);
    ExtractionResult extraction = extraction(tenantId, "RFQ");
    UUID lineA = saveLine(tenantId, extraction.getId(), 1, sku, "2", new BigDecimal("5"));
    UUID runId = validationRunService.run(extraction.getId(), "FULL").getId();
    return new Run(runId, lineA, null);
  }

  private Run cleanRunTwoLines(UUID tenantId, String tag) {
    String skuA = "SKU-" + tag + "-A";
    String skuB = "SKU-" + tag + "-B";
    Location location = seedCustomer(tenantId, tag);
    seedProduct(tenantId, skuA, location);
    seedProduct(tenantId, skuB, location);
    ExtractionResult extraction = extraction(tenantId, "RFQ");
    UUID lineA = saveLine(tenantId, extraction.getId(), 1, skuA, "2", new BigDecimal("5"));
    UUID lineB = saveLine(tenantId, extraction.getId(), 2, skuB, "3", new BigDecimal("7"));
    UUID runId = validationRunService.run(extraction.getId(), "FULL").getId();
    return new Run(runId, lineA, lineB);
  }

  private Run blockedRun(UUID tenantId, String tag) {
    String sku = "SKU-" + tag;
    Location location = seedCustomer(tenantId, tag);
    seedProduct(tenantId, sku, location);
    ExtractionResult extraction = extraction(tenantId, "RFQ");
    UUID lineA = saveLine(tenantId, extraction.getId(), 1, sku, "0", new BigDecimal("0"));
    UUID runId = validationRunService.run(extraction.getId(), "FULL").getId();
    return new Run(runId, lineA, null);
  }

  // One customer per run (avoids ambiguous customer match across multiple seeded products).
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

  private UUID saveLine(UUID tenantId, UUID extractionResultId, int lineNumber, String sku, String rawQty, BigDecimal normalizedQty) {
    ExtractedLineItem line = lines.save(new ExtractedLineItem(tenantId, extractionResultId, lineNumber, sku, sku + " Filter", rawQty, normalizedQty, "EA", "EA", new BigDecimal("0.95"), null, NOW));
    return line.getId();
  }
}
