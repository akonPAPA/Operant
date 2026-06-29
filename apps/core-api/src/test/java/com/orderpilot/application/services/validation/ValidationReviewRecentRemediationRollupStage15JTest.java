package com.orderpilot.application.services.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.ValidationReviewCommandDtos;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationApprovalRequestCommand;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationIssueResolutionRequest;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewCorrectionRequest;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftRecentRemediationRollupItem;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftRecentRemediationRollupResponse;
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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-15J — bounded recent-window remediation rollup tile.
 *
 * <p>Proves the rollup aggregates the SAME structured per-draft derivation used by the OP-CAP-15G/15I queue
 * rows over a bounded recent window: review-origin drafts are counted separately from all inspected drafts,
 * non-review-origin drafts contribute an explicit unavailable-lineage entry (never fabricated lineage),
 * action/line counts and the max action timestamp aggregate correctly, limitation codes are unique and
 * deterministic, foreign-tenant data never leaks, and {@code topLimitedDrafts} is bounded and sorted.
 */
@SpringBootTest
@ActiveProfiles("test")
class ValidationReviewRecentRemediationRollupStage15JTest {
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

  @Test
  void emptyTenantReturnsZeroRollup() {
    TenantContext.setTenantId(UUID.randomUUID());
    ValidationReviewDraftRecentRemediationRollupResponse rollup = queryService.recentRemediationRollup(null);

    assertThat(rollup.inspectedDraftCount()).isZero();
    assertThat(rollup.reviewOriginDraftCount()).isZero();
    assertThat(rollup.remediationActionCount()).isZero();
    assertThat(rollup.latestRemediationActionAt()).isNull();
    assertThat(rollup.limitationCodes()).isEmpty();
    assertThat(rollup.topLimitedDrafts()).isEmpty();
    assertThat(rollup.limit()).isEqualTo(ValidationReviewCommandDtos.DEFAULT_RECENT_ROLLUP_LIMIT);
    assertThat(rollup.externalExecution()).isEqualTo("DISABLED");
  }

  @Test
  void aggregatesStructuredActionsAndLineCountsForReviewOriginDraft() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "AGG");
    ValidationIssue issue = issues.save(new ValidationIssue(tenantId, run.runId, run.extractionResultId, run.lineA, null, "MARGIN_BELOW_GUARDRAIL", "ERROR", "blocking", "{}", NOW));
    reviewCommandService.resolveIssue(
        run.runId, issue.getId(),
        new ValidationIssueResolutionRequest("RESOLVED", "ok", null, null), ACTOR_ID);
    reviewCommandService.submitCorrection(
        run.runId,
        new ValidationReviewCorrectionRequest("LINE_ITEM", run.lineA, null, "5", null, "fix", null),
        ACTOR_ID);
    draftBridge.createDraftQuote(run.runId, UUID.randomUUID());
    reviewCommandService.requestApproval(
        run.runId,
        new ValidationApprovalRequestCommand(run.lineA, "OPERATOR_CORRECTION_REVIEW", "sign-off"),
        ACTOR_ID);

    ValidationReviewDraftRecentRemediationRollupResponse rollup = queryService.recentRemediationRollup(null);

    assertThat(rollup.inspectedDraftCount()).isEqualTo(1);
    assertThat(rollup.reviewOriginDraftCount()).isEqualTo(1);
    assertThat(rollup.lineageAvailableDraftCount()).isEqualTo(1);
    assertThat(rollup.lineageUnavailableDraftCount()).isZero();
    assertThat(rollup.correctionActionCount()).isEqualTo(1);
    assertThat(rollup.issueResolutionActionCount()).isEqualTo(1);
    assertThat(rollup.approvalActionCount()).isEqualTo(1);
    assertThat(rollup.remediationActionCount()).isEqualTo(3);
    assertThat(rollup.traceableDraftLineCount()).isEqualTo(1);
    assertThat(rollup.remediatedDraftLineCount()).isEqualTo(1);
    assertThat(rollup.draftLineCount()).isEqualTo(1);
    assertThat(rollup.latestRemediationActionAt()).isNotNull();
    assertThat(rollup.limitationCodes()).isEmpty();
  }

  @Test
  void countsReviewOriginSeparatelyFromInspectedAndNonReviewOriginIsUnavailable() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    // One review-origin draft with remediation.
    Run run = cleanRun(tenantId, "MIX");
    reviewCommandService.submitCorrection(
        run.runId,
        new ValidationReviewCorrectionRequest("LINE_ITEM", run.lineA, null, "5", null, "fix", null),
        ACTOR_ID);
    draftBridge.createDraftQuote(run.runId, UUID.randomUUID());
    // One non-review-origin manual draft (no source validation run).
    draftQuotes.save(new DraftQuote(tenantId, "DQ-MANUAL", null, UUID.randomUUID(), null, null, "DRAFT", "USD", null, NOW));

    ValidationReviewDraftRecentRemediationRollupResponse rollup = queryService.recentRemediationRollup(null);

    assertThat(rollup.inspectedDraftCount()).isEqualTo(2);
    assertThat(rollup.reviewOriginDraftCount()).isEqualTo(1);
    assertThat(rollup.lineageAvailableDraftCount()).isEqualTo(1);
    assertThat(rollup.lineageUnavailableDraftCount()).isEqualTo(1);
    assertThat(rollup.limitationCodes()).contains(ValidationReviewCommandDtos.REMEDIATION_DRAFT_NOT_REVIEW_ORIGIN);
    // The manual draft is surfaced as a limited draft with unavailable lineage and no fabricated counts.
    ValidationReviewDraftRecentRemediationRollupItem manual = rollup.topLimitedDrafts().stream()
        .filter(i -> i.sourceValidationRunId() == null).findFirst().orElseThrow();
    assertThat(manual.remediationLineageAvailable()).isFalse();
    assertThat(manual.remediationActionCount()).isZero();
    assertThat(manual.latestRemediationActionAt()).isNull();
    assertThat(manual.limitationCodes()).containsExactly(ValidationReviewCommandDtos.REMEDIATION_DRAFT_NOT_REVIEW_ORIGIN);
  }

  @Test
  void missingSourceLineageReviewOriginDraftIsUnavailableWithToken() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    // Review-origin draft (run set) whose only line carries no source line id -> lineage unavailable.
    UUID runId = UUID.randomUUID();
    DraftQuote quote = draftQuotes.save(new DraftQuote(tenantId, "DQ-NOSRC", null, UUID.randomUUID(), runId, UUID.randomUUID(), "DRAFT", "USD", null, NOW));
    draftQuoteLines.save(new DraftQuoteLine(tenantId, quote.getId(), 1, "raw", "RAW", null, null, null, BigDecimal.ONE, "EA", null, null, null, null, "[]", "DRAFT", "NEEDS_REVIEW", NOW));

    ValidationReviewDraftRecentRemediationRollupResponse rollup = queryService.recentRemediationRollup(null);

    assertThat(rollup.reviewOriginDraftCount()).isEqualTo(1);
    assertThat(rollup.lineageAvailableDraftCount()).isZero();
    assertThat(rollup.lineageUnavailableDraftCount()).isEqualTo(1);
    assertThat(rollup.limitationCodes()).containsExactly(ValidationReviewCommandDtos.REMEDIATION_LINEAGE_MISSING);
  }

  @Test
  void latestRemediationActionAtIsMaxAcrossWindow() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run a = cleanRun(tenantId, "LA");
    reviewCommandService.submitCorrection(
        a.runId,
        new ValidationReviewCorrectionRequest("LINE_ITEM", a.lineA, null, "5", null, "fix", null),
        ACTOR_ID);
    draftBridge.createDraftQuote(a.runId, UUID.randomUUID());
    Run b = cleanRun(tenantId, "LB");
    reviewCommandService.submitCorrection(
        b.runId,
        new ValidationReviewCorrectionRequest("LINE_ITEM", b.lineA, null, "5", null, "fix", null),
        ACTOR_ID);
    draftBridge.createDraftQuote(b.runId, UUID.randomUUID());

    ValidationReviewDraftRecentRemediationRollupResponse rollup = queryService.recentRemediationRollup(null);

    assertThat(rollup.reviewOriginDraftCount()).isEqualTo(2);
    assertThat(rollup.remediationActionCount()).isEqualTo(2);
    // Window-level latest equals the max persisted STRUCTURED REMEDIATION action createdAt (not draft-create
    // or other operator actions), read from the same store the rollup derives from.
    Instant expectedMax = operatorActions.findTop25ByTenantIdOrderByCreatedAtDesc(tenantId).stream()
        .filter(action -> "VALIDATION_REVIEW_LINE_ITEM_CORRECTED".equals(action.getActionType()))
        .map(action -> action.getCreatedAt())
        .max(Instant::compareTo)
        .orElseThrow();
    assertThat(rollup.latestRemediationActionAt()).isEqualTo(expectedMax);
  }

  @Test
  void foreignTenantDraftsAndActionsDoNotAffectRollup() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantB);
    Run runB = cleanRun(tenantB, "TB");
    reviewCommandService.submitCorrection(
        runB.runId,
        new ValidationReviewCorrectionRequest("LINE_ITEM", runB.lineA, null, "5", null, "foreign", null),
        ACTOR_ID);
    draftBridge.createDraftQuote(runB.runId, UUID.randomUUID());

    TenantContext.setTenantId(tenantA);
    ValidationReviewDraftRecentRemediationRollupResponse rollup = queryService.recentRemediationRollup(null);

    assertThat(rollup.inspectedDraftCount()).isZero();
    assertThat(rollup.remediationActionCount()).isZero();
    assertThat(rollup.latestRemediationActionAt()).isNull();
  }

  @Test
  void limitIsBoundedAndDeterministic() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    for (int i = 0; i < 3; i++) {
      Run run = cleanRun(tenantId, "L" + i);
      draftBridge.createDraftQuote(run.runId, UUID.randomUUID());
    }

    ValidationReviewDraftRecentRemediationRollupResponse limited = queryService.recentRemediationRollup(2);
    assertThat(limited.limit()).isEqualTo(2);
    assertThat(limited.inspectedDraftCount()).isEqualTo(2);

    // Over-max clamps to MAX; zero/negative falls back to default.
    assertThat(queryService.recentRemediationRollup(9999).limit()).isEqualTo(ValidationReviewCommandDtos.MAX_RECENT_ROLLUP_LIMIT);
    assertThat(queryService.recentRemediationRollup(0).limit()).isEqualTo(ValidationReviewCommandDtos.DEFAULT_RECENT_ROLLUP_LIMIT);
  }

  @Test
  void topLimitedDraftsIsBoundedAndSorted() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    // 12 non-review-origin drafts -> all limited; topLimitedDrafts must cap at MAX_TOP_LIMITED_DRAFTS.
    for (int i = 0; i < 12; i++) {
      draftQuotes.save(new DraftQuote(tenantId, "DQ-NRO-" + i, null, UUID.randomUUID(), null, null, "DRAFT", "USD", null, NOW.plusSeconds(i)));
    }

    ValidationReviewDraftRecentRemediationRollupResponse rollup = queryService.recentRemediationRollup(null);

    assertThat(rollup.lineageUnavailableDraftCount()).isEqualTo(12);
    assertThat(rollup.topLimitedDrafts()).hasSize(ValidationReviewCommandDtos.MAX_TOP_LIMITED_DRAFTS);
    // Deterministic order: latestRemediationActionAt desc (all null here), then draftId ascending.
    for (int i = 1; i < rollup.topLimitedDrafts().size(); i++) {
      String prev = rollup.topLimitedDrafts().get(i - 1).draftId().toString();
      String cur = rollup.topLimitedDrafts().get(i).draftId().toString();
      assertThat(prev.compareTo(cur)).isLessThanOrEqualTo(0);
    }
  }

  // --- helpers -----------------------------------------------------------------------------------

  private record Run(UUID runId, UUID extractionResultId, UUID lineA) {}

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
