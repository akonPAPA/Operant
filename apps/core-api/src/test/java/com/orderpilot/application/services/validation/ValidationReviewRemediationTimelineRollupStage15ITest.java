package com.orderpilot.application.services.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.ValidationReviewCommandDtos;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.LineageTimelineEntry;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationApprovalRequestCommand;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationIssueResolutionRequest;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewCorrectionRequest;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftQueueItem;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftQueueResponse;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftRemediationLineageDetail;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftRemediationLineageLine;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftRemediationRollup;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftResult;
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
import com.orderpilot.domain.workspace.OperatorActionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-15I — read-only remediation timeline (15H detail) + compact queue rollup (15G queue) polish.
 *
 * <p>Proves the per-line timeline is a deterministic, ordered projection of the SAME structured OP-CAP-14C
 * operator actions 15H attaches (corrections, issue resolutions, approvals) — field-level corrections are
 * excluded, summaries are deterministic backend text (not raw notes), and an action-free line yields an
 * empty (never null) timeline. Proves the queue rollup is derived from the same structured batch data with
 * no cross-tenant leakage, counts that match the 15H detail, and a max-createdAt latest timestamp.
 */
@SpringBootTest
@ActiveProfiles("test")
class ValidationReviewRemediationTimelineRollupStage15ITest {
  private static final Instant NOW = Instant.parse("2026-06-09T00:00:00Z");

  @Autowired private ValidationReviewDraftRemediationLineageService lineageService;
  @Autowired private ValidationReviewDraftQueryService queryService;
  @Autowired private ValidationReviewDraftCommandService draftBridge;
  @Autowired private ValidationReviewCommandService reviewCommandService;
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
  @Autowired private DraftQuoteLineRepository draftQuoteLines;
  @Autowired private OperatorActionRepository operatorActions;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  // --- detail timeline -----------------------------------------------------------------------------

  @Test
  void lineTimelineIncludesCorrectionResolutionApprovalInDeterministicOrder() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "TL");
    ValidationIssue issue = issues.save(new ValidationIssue(tenantId, run.runId, run.extractionResultId, run.lineA, null, "MARGIN_BELOW_GUARDRAIL", "ERROR", "blocking", "{}", NOW));
    reviewCommandService.resolveIssue(run.runId, issue.getId(), new ValidationIssueResolutionRequest("RESOLVED", "fixed", null, UUID.randomUUID(), null));
    reviewCommandService.submitCorrection(run.runId, new ValidationReviewCorrectionRequest("LINE_ITEM", run.lineA, null, "5", null, "fix qty", UUID.randomUUID(), null));
    ValidationReviewDraftResult draft = draftBridge.createDraftQuote(run.runId, UUID.randomUUID());
    reviewCommandService.requestApproval(run.runId, new ValidationApprovalRequestCommand(run.lineA, "OPERATOR_CORRECTION_REVIEW", "sign-off", UUID.randomUUID()));

    ValidationReviewDraftRemediationLineageDetail detail = lineageService.remediationLineage("QUOTE", draft.draftId());
    ValidationReviewDraftRemediationLineageLine line = tracedLine(detail, run.lineA);

    assertThat(line.timeline()).hasSize(3);
    // Deterministic order: createdAt ascending then actionId.
    for (int i = 1; i < line.timeline().size(); i++) {
      LineageTimelineEntry prev = line.timeline().get(i - 1);
      LineageTimelineEntry cur = line.timeline().get(i);
      assertThat(prev.createdAt()).isBeforeOrEqualTo(cur.createdAt());
    }
    Set<String> categories = line.timeline().stream().map(LineageTimelineEntry::category).collect(Collectors.toSet());
    assertThat(categories).containsExactlyInAnyOrder(
        ValidationReviewCommandDtos.LINEAGE_CATEGORY_CORRECTION,
        ValidationReviewCommandDtos.LINEAGE_CATEGORY_ISSUE_RESOLUTION,
        ValidationReviewCommandDtos.LINEAGE_CATEGORY_APPROVAL);
    assertThat(line.timeline()).allSatisfy(e -> assertThat(e.actionId()).isNotNull());
  }

  @Test
  void timelineExcludesFieldLevelCorrections() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "FLD");
    // A field-level correction (not line-scoped) plus a line correction on the drafted line.
    reviewCommandService.submitCorrection(run.runId, new ValidationReviewCorrectionRequest("FIELD", run.fieldA, "Acme Fixed", null, null, "fix field", UUID.randomUUID(), null));
    reviewCommandService.submitCorrection(run.runId, new ValidationReviewCorrectionRequest("LINE_ITEM", run.lineA, null, "5", null, "fix qty", UUID.randomUUID(), null));
    ValidationReviewDraftResult draft = draftBridge.createDraftQuote(run.runId, UUID.randomUUID());

    ValidationReviewDraftRemediationLineageDetail detail = lineageService.remediationLineage("QUOTE", draft.draftId());
    ValidationReviewDraftRemediationLineageLine line = tracedLine(detail, run.lineA);

    assertThat(line.timeline()).hasSize(1);
    assertThat(line.timeline().get(0).category()).isEqualTo(ValidationReviewCommandDtos.LINEAGE_CATEGORY_CORRECTION);
    assertThat(line.timeline()).noneSatisfy(e -> assertThat(e.actionType()).contains("FIELD"));
  }

  @Test
  void timelineExposesDeterministicSummaryNotRawNotes() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "SUM");
    String secretNote = "SECRET-NOTE-9281";
    reviewCommandService.submitCorrection(run.runId, new ValidationReviewCorrectionRequest("LINE_ITEM", run.lineA, null, "5", null, secretNote, UUID.randomUUID(), null));
    ValidationReviewDraftResult draft = draftBridge.createDraftQuote(run.runId, UUID.randomUUID());

    ValidationReviewDraftRemediationLineageDetail detail = lineageService.remediationLineage("QUOTE", draft.draftId());
    LineageTimelineEntry entry = tracedLine(detail, run.lineA).timeline().get(0);

    assertThat(entry.summary()).isNotBlank();
    assertThat(entry.summary()).doesNotContain(secretNote);
    assertThat(entry.actionType()).isEqualTo("VALIDATION_REVIEW_LINE_ITEM_CORRECTED");
  }

  @Test
  void emptyTimelineIsReturnedAsListNotNull() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "EMPTY");
    ValidationReviewDraftResult draft = draftBridge.createDraftQuote(run.runId, UUID.randomUUID());

    ValidationReviewDraftRemediationLineageDetail detail = lineageService.remediationLineage("QUOTE", draft.draftId());

    assertThat(detail.lines()).isNotEmpty();
    assertThat(detail.lines()).allSatisfy(l -> assertThat(l.timeline()).isNotNull().isEmpty());
  }

  // --- queue rollup --------------------------------------------------------------------------------

  @Test
  void queueRowShowsRollupAvailableWithCountsForRemediatedDraft() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "RUP");
    reviewCommandService.submitCorrection(run.runId, new ValidationReviewCorrectionRequest("LINE_ITEM", run.lineA, null, "5", null, "fix", UUID.randomUUID(), null));
    draftBridge.createDraftQuote(run.runId, UUID.randomUUID());

    ValidationReviewDraftRemediationRollup rollup = only(queryService.reviewDraftQueue(null, null, null, null)).remediationRollup();

    assertThat(rollup.remediationLineageAvailable()).isTrue();
    assertThat(rollup.remediationActionCount()).isEqualTo(1);
    assertThat(rollup.remediatedLineCount()).isEqualTo(1);
    assertThat(rollup.traceableLineCount()).isEqualTo(1);
    assertThat(rollup.latestRemediationActionAt()).isNotNull();
    assertThat(rollup.limitationCodes()).isEmpty();
  }

  @Test
  void queueRowShowsRollupUnavailableForMissingLineage() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID runId = UUID.randomUUID();
    DraftQuote quote = draftQuotes.save(new DraftQuote(tenantId, "DQ-NOSRC", null, UUID.randomUUID(), runId, UUID.randomUUID(), "DRAFT", "USD", null, NOW));
    draftQuoteLines.save(new DraftQuoteLine(tenantId, quote.getId(), 1, "raw", "RAW", null, null, null, BigDecimal.ONE, "EA", null, null, null, null, "[]", "DRAFT", "NEEDS_REVIEW", NOW));

    ValidationReviewDraftRemediationRollup rollup = only(queryService.reviewDraftQueue(null, null, null, null)).remediationRollup();

    assertThat(rollup.remediationLineageAvailable()).isFalse();
    assertThat(rollup.remediationActionCount()).isZero();
    assertThat(rollup.remediatedLineCount()).isZero();
    assertThat(rollup.traceableLineCount()).isZero();
    assertThat(rollup.latestRemediationActionAt()).isNull();
    assertThat(rollup.limitationCodes()).contains(ValidationReviewCommandDtos.REMEDIATION_LINEAGE_MISSING);
  }

  @Test
  void rollupActionCountMatches15HDetailDerivation() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "MATCH");
    ValidationIssue issue = issues.save(new ValidationIssue(tenantId, run.runId, run.extractionResultId, run.lineA, null, "MARGIN_BELOW_GUARDRAIL", "ERROR", "blocking", "{}", NOW));
    reviewCommandService.resolveIssue(run.runId, issue.getId(), new ValidationIssueResolutionRequest("RESOLVED", "ok", null, UUID.randomUUID(), null));
    reviewCommandService.submitCorrection(run.runId, new ValidationReviewCorrectionRequest("LINE_ITEM", run.lineA, null, "5", null, "fix", UUID.randomUUID(), null));
    ValidationReviewDraftResult draft = draftBridge.createDraftQuote(run.runId, UUID.randomUUID());
    reviewCommandService.requestApproval(run.runId, new ValidationApprovalRequestCommand(run.lineA, "OPERATOR_CORRECTION_REVIEW", "sign-off", UUID.randomUUID()));

    ValidationReviewDraftRemediationLineageDetail detail = lineageService.remediationLineage("QUOTE", draft.draftId());
    ValidationReviewDraftRemediationRollup rollup = only(queryService.reviewDraftQueue(null, null, null, null)).remediationRollup();

    int detailActionTotal = detail.correctionActionCount() + detail.issueResolutionActionCount() + detail.approvalActionCount();
    assertThat(rollup.remediationActionCount()).isEqualTo(detailActionTotal);
    assertThat(rollup.remediatedLineCount()).isEqualTo(detail.remediatedDraftLineCount());
    assertThat(rollup.traceableLineCount()).isEqualTo(detail.traceableDraftLineCount());
  }

  @Test
  void crossTenantActionsDoNotLeakIntoRollup() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantB);
    Run runB = cleanRun(tenantB, "TB");
    reviewCommandService.submitCorrection(runB.runId, new ValidationReviewCorrectionRequest("LINE_ITEM", runB.lineA, null, "5", null, "foreign", UUID.randomUUID(), null));

    TenantContext.setTenantId(tenantA);
    Run runA = cleanRun(tenantA, "TA");
    draftBridge.createDraftQuote(runA.runId, UUID.randomUUID());

    ValidationReviewDraftRemediationRollup rollup = only(queryService.reviewDraftQueue(null, null, null, null)).remediationRollup();
    assertThat(rollup.remediationActionCount()).isZero();
    assertThat(rollup.remediatedLineCount()).isZero();
    assertThat(rollup.latestRemediationActionAt()).isNull();
  }

  @Test
  void latestRemediationActionAtIsMaxCreatedAtAcrossAttachedActions() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "LATE");
    reviewCommandService.submitCorrection(run.runId,
        new ValidationReviewCorrectionRequest("LINE_ITEM", run.lineA, null, "5", null, "fix", UUID.randomUUID(), null));
    draftBridge.createDraftQuote(run.runId, UUID.randomUUID());
    reviewCommandService.requestApproval(run.runId,
        new ValidationApprovalRequestCommand(run.lineA, "OPERATOR_CORRECTION_REVIEW", "sign-off", UUID.randomUUID()));

    ValidationReviewDraftRemediationRollup rollup = only(queryService.reviewDraftQueue(null, null, null, null)).remediationRollup();

    // Max createdAt across this tenant's persisted structured actions (correction + approval), read from the
    // same store the rollup derives from — avoids in-memory vs DB timestamp-precision drift.
    Instant expectedMax = operatorActions.findTop25ByTenantIdOrderByCreatedAtDesc(tenantId).get(0).getCreatedAt();
    assertThat(rollup.latestRemediationActionAt()).isEqualTo(expectedMax);
  }

  // --- helpers -----------------------------------------------------------------------------------

  private record Run(UUID runId, UUID extractionResultId, UUID lineA, UUID fieldA) {}

  private ValidationReviewDraftQueueItem only(ValidationReviewDraftQueueResponse response) {
    assertThat(response.items()).hasSize(1);
    return response.items().get(0);
  }

  private ValidationReviewDraftRemediationLineageLine tracedLine(ValidationReviewDraftRemediationLineageDetail detail, UUID sourceLineId) {
    return detail.lines().stream().filter(l -> sourceLineId.equals(l.sourceLineItemId())).findFirst().orElseThrow();
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
    ExtractedField field = fields.save(new ExtractedField(tenantId, extraction.getId(), "customer_hint", customerName, customerName, "customer_hint", new BigDecimal("0.95"), null, NOW));
    ExtractedLineItem line = lines.save(new ExtractedLineItem(tenantId, extraction.getId(), 1, sku, sku + " Filter", "2", new BigDecimal("5"), "EA", "EA", new BigDecimal("0.95"), null, NOW));
    UUID runId = validationRunService.run(extraction.getId(), "FULL").getId();
    return new Run(runId, extraction.getId(), line.getId(), field.getId());
  }
}
