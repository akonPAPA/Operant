package com.orderpilot.application.services.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationIssueResolutionRequest;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewCorrectionRequest;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftQueueItem;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftQueueResponse;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftRemediationSummary;
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
import com.orderpilot.domain.workspace.DraftQuote;
import com.orderpilot.domain.workspace.DraftQuoteLine;
import com.orderpilot.domain.workspace.DraftQuoteLineRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.domain.workspace.OperatorAction;
import com.orderpilot.domain.workspace.OperatorActionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-15G — read-only remediation outcome summary on the review-origin draft queue.
 *
 * <p>Proves the summary is derived ONLY from structured tenant-scoped records with stable ids (draft line
 * source ids, validation issue ids, OperatorAction target ids): line counts always present; remediated
 * counts only when structured line/issue→action linkage exists; cross-tenant and cross-run actions ignored;
 * free-text/non-structured actions never counted; plain drafted lines are not "remediated"; missing source
 * lineage yields a safe available=false summary.
 */
@SpringBootTest
@ActiveProfiles("test")
class ValidationReviewDraftQueueRemediationStage15GTest {
  private static final Instant NOW = Instant.parse("2026-06-09T00:00:00Z");
  private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-4000-8000-000000000015");

  @Autowired private ValidationReviewDraftQueryService queryService;
  @Autowired private ValidationReviewDraftCommandService draftBridge;
  @Autowired private ValidationReviewCommandService reviewCommandService;
  @Autowired private ValidationRunService validationRunService;
  @Autowired private ExtractionResultRepository extractionResults;
  @Autowired private ExtractedFieldRepository fields;
  @Autowired private ExtractedLineItemRepository lines;
  @Autowired private ValidationIssueRepository issues;
  @Autowired private OperatorActionRepository operatorActions;
  @Autowired private CustomerAccountRepository customers;
  @Autowired private ProductRepository products;
  @Autowired private LocationRepository locations;
  @Autowired private InventorySnapshotRepository inventory;
  @Autowired private PriceRuleRepository prices;
  @Autowired private DraftQuoteRepository draftQuotes;
  @Autowired private DraftQuoteLineRepository draftQuoteLines;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void summaryIncludesDraftLineCountAndZeroRemediationForPlainDraft() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "PLAIN");
    draftBridge.createDraftQuote(run.runId, UUID.randomUUID());

    ValidationReviewDraftRemediationSummary summary = only(queryService.reviewDraftQueue(null, null, null, null)).remediationSummary();

    assertThat(summary.available()).isTrue();
    assertThat(summary.draftLineCount()).isEqualTo(1);
    // A plain drafted line with no structured remediation action is NOT counted as remediated.
    assertThat(summary.remediatedDraftLineCount()).isZero();
    assertThat(summary.correctionActionCount()).isZero();
    assertThat(summary.issueResolutionActionCount()).isZero();
  }

  @Test
  void lineCorrectionActionLinkedToDraftedSourceLineIsCounted() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "CORR");
    // Structured 14C line-item correction on the drafted source line (writes a structured OperatorAction).
    reviewCommandService.submitCorrection(run.runId, new ValidationReviewCorrectionRequest(
        "LINE_ITEM", run.lineA, null, "5", null, "fix qty", null), ACTOR_ID);
    draftBridge.createDraftQuote(run.runId, UUID.randomUUID());

    ValidationReviewDraftRemediationSummary summary = only(queryService.reviewDraftQueue(null, null, null, null)).remediationSummary();

    assertThat(summary.available()).isTrue();
    assertThat(summary.correctionActionCount()).isEqualTo(1);
    assertThat(summary.remediatedDraftLineCount()).isEqualTo(1);
  }

  @Test
  void issueResolutionLinkedToDraftedLineIsCounted() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "ISS");
    ValidationIssue issue = issues.save(new ValidationIssue(tenantId, run.runId, run.extractionResultId, run.lineA, null, "MARGIN_BELOW_GUARDRAIL", "ERROR", "blocking", "{}", NOW));
    // Resolve via the structured 14C command (writes a structured OperatorAction on the issue id).
    reviewCommandService.resolveIssue(
        run.runId, issue.getId(),
        new ValidationIssueResolutionRequest("RESOLVED", "fixed", null, null), ACTOR_ID);
    draftBridge.createDraftQuote(run.runId, UUID.randomUUID());

    ValidationReviewDraftRemediationSummary summary = only(queryService.reviewDraftQueue(null, null, null, null)).remediationSummary();

    assertThat(summary.issueResolutionActionCount()).isEqualTo(1);
    assertThat(summary.remediatedDraftLineCount()).isEqualTo(1);
  }

  @Test
  void actionFromAnotherTenantIsIgnored() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantB);
    Run runB = cleanRun(tenantB, "TB");
    reviewCommandService.submitCorrection(runB.runId, new ValidationReviewCorrectionRequest(
        "LINE_ITEM", runB.lineA, null, "5", null, "foreign fix", null), ACTOR_ID);

    TenantContext.setTenantId(tenantA);
    Run runA = cleanRun(tenantA, "TA");
    draftBridge.createDraftQuote(runA.runId, UUID.randomUUID());

    ValidationReviewDraftRemediationSummary summary = only(queryService.reviewDraftQueue(null, null, null, null)).remediationSummary();
    assertThat(summary.correctionActionCount()).isZero();
    assertThat(summary.remediatedDraftLineCount()).isZero();
  }

  @Test
  void actionFromAnotherRunIsIgnored() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run drafted = cleanRun(tenantId, "RA");
    Run other = cleanRun(tenantId, "RB");
    // Correction on a DIFFERENT run's line — not a source line of the drafted run.
    reviewCommandService.submitCorrection(other.runId, new ValidationReviewCorrectionRequest(
        "LINE_ITEM", other.lineA, null, "5", null, "other run fix", null), ACTOR_ID);
    draftBridge.createDraftQuote(drafted.runId, UUID.randomUUID());

    ValidationReviewDraftQueueResponse response = queryService.reviewDraftQueue("QUOTE", null, null, null);
    ValidationReviewDraftRemediationSummary summary = response.items().stream()
        .filter(i -> i.sourceValidationRunId().equals(drafted.runId)).findFirst().orElseThrow().remediationSummary();
    assertThat(summary.correctionActionCount()).isZero();
    assertThat(summary.remediatedDraftLineCount()).isZero();
  }

  @Test
  void freeTextOrNonStructuredActionIsNotCounted() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "FREE");
    // A generic action whose message *mentions* the line but is not a structured correction/resolution.
    operatorActions.save(new OperatorAction(tenantId, UUID.randomUUID(), "DRAFT_QUOTE", run.lineA, "OTHER",
        "operator corrected line " + run.lineA + " and resolved issue", "{}", NOW));
    draftBridge.createDraftQuote(run.runId, UUID.randomUUID());

    ValidationReviewDraftRemediationSummary summary = only(queryService.reviewDraftQueue(null, null, null, null)).remediationSummary();
    assertThat(summary.correctionActionCount()).isZero();
    assertThat(summary.issueResolutionActionCount()).isZero();
    assertThat(summary.remediatedDraftLineCount()).isZero();
  }

  @Test
  void missingSourceLineLineageReturnsAvailableFalse() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    // Review-origin draft (sourceValidationRunId set) whose line has NO source extracted-line-item id.
    UUID runId = UUID.randomUUID();
    DraftQuote quote = draftQuotes.save(new DraftQuote(tenantId, "DQ-NOSRC", null, UUID.randomUUID(), runId, UUID.randomUUID(), "DRAFT", "USD", null, NOW));
    draftQuoteLines.save(new DraftQuoteLine(tenantId, quote.getId(), 1, "raw", "RAW", null, null, null, BigDecimal.ONE, "EA", null, null, null, null, "[]", "DRAFT", "NEEDS_REVIEW", NOW));

    ValidationReviewDraftRemediationSummary summary = only(queryService.reviewDraftQueue(null, null, null, null)).remediationSummary();
    assertThat(summary.available()).isFalse();
    assertThat(summary.draftLineCount()).isEqualTo(1);
    assertThat(summary.limitations()).contains("structured_action_lineage_missing");
  }

  @Test
  void multipleDraftsEachResolveOwnRemediationLineage() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run a = cleanRun(tenantId, "M1");
    Run b = cleanRun(tenantId, "M2");
    reviewCommandService.submitCorrection(
        a.runId,
        new ValidationReviewCorrectionRequest("LINE_ITEM", a.lineA, null, "5", null, "fix", null),
        ACTOR_ID);
    draftBridge.createDraftQuote(a.runId, UUID.randomUUID());
    draftBridge.createDraftQuote(b.runId, UUID.randomUUID());

    ValidationReviewDraftQueueResponse response = queryService.reviewDraftQueue(null, null, null, null);
    assertThat(response.items()).hasSize(2);
    ValidationReviewDraftRemediationSummary aSummary = response.items().stream().filter(i -> i.sourceValidationRunId().equals(a.runId)).findFirst().orElseThrow().remediationSummary();
    ValidationReviewDraftRemediationSummary bSummary = response.items().stream().filter(i -> i.sourceValidationRunId().equals(b.runId)).findFirst().orElseThrow().remediationSummary();
    assertThat(aSummary.correctionActionCount()).isEqualTo(1);
    assertThat(bSummary.correctionActionCount()).isZero();
  }

  // --- helpers -----------------------------------------------------------------------------------

  private record Run(UUID runId, UUID extractionResultId, UUID lineA) {}

  private ValidationReviewDraftQueueItem only(ValidationReviewDraftQueueResponse response) {
    assertThat(response.items()).hasSize(1);
    return response.items().get(0);
  }

  private Run cleanRun(UUID tenantId, String tag) {
    String sku = "SKU-" + tag;
    String customerName = "Acme " + tag;
    Location location = locations.save(new Location(tenantId, "LOC-" + tag, "LOC-" + tag, "WAREHOUSE", null, "Almaty", "KZ", true, NOW));
    customers.save(new CustomerAccount(tenantId, null, "ACME-" + tag, customerName, customerName, null, "ACTIVE", "USD", location.getId(), NOW));
    Product product = products.save(new Product(tenantId, sku, sku + " Filter", sku + " Filter", "Filters", "Brand", "Maker", "EA", "ACTIVE", new BigDecimal("10"), "USD", NOW));
    inventory.save(new InventorySnapshot(tenantId, product.getId(), location.getId(), new BigDecimal("50"), new BigDecimal("50"), BigDecimal.ZERO, NOW, "TEST", null, NOW));
    prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    ExtractionResult extraction = extractionResults.save(new ExtractionResult(tenantId, UUID.randomUUID(), "CHANNEL_MESSAGE", UUID.randomUUID(), "RFQ", "message", new BigDecimal("0.95"), "{}", "ready_for_validation", NOW));
    fields.save(new ExtractedField(tenantId, extraction.getId(), "customer_hint", customerName, customerName, "customer_hint", new BigDecimal("0.95"), null, NOW));
    ExtractedLineItem line = lines.save(new ExtractedLineItem(tenantId, extraction.getId(), 1, sku, sku + " Filter", "2", new BigDecimal("5"), "EA", "EA", new BigDecimal("0.95"), null, NOW));
    UUID runId = validationRunService.run(extraction.getId(), "FULL").getId();
    return new Run(runId, extraction.getId(), line.getId());
  }
}
