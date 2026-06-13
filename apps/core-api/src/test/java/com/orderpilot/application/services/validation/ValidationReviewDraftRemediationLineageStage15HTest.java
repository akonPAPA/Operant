package com.orderpilot.application.services.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.ValidationReviewCommandDtos;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationApprovalRequestCommand;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationIssueResolutionRequest;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewCorrectionRequest;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftRemediationLineageDetail;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftRemediationLineageLine;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftResult;
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
import com.orderpilot.domain.workspace.DraftQuote;
import com.orderpilot.domain.workspace.DraftQuoteLine;
import com.orderpilot.domain.workspace.DraftQuoteLineRepository;
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
 * OP-CAP-15H — read-only remediation lineage DETAIL for one review-origin draft.
 *
 * <p>Proves the detail is derived ONLY from structured, tenant-scoped records (draft line source ids,
 * validation issue ids, approval requirement ids, structured OperatorAction target ids) written by the
 * real OP-CAP-14C command service: line-item corrections, issue resolutions and approvals attach to the
 * drafted source line; run-scoped actions that map to no drafted line surface as unattached; missing
 * source lineage and non-review-origin drafts yield safe, explicit limitations; another tenant cannot read
 * the lineage.
 */
@SpringBootTest
@ActiveProfiles("test")
class ValidationReviewDraftRemediationLineageStage15HTest {
  private static final Instant NOW = Instant.parse("2026-06-09T00:00:00Z");

  @Autowired private ValidationReviewDraftRemediationLineageService lineageService;
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

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void quoteDraftWithCorrectionActionAppearsInLineLineage() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "CORR");
    // Real 14C structured line-item correction on the drafted source line.
    reviewCommandService.submitCorrection(run.runId, new ValidationReviewCorrectionRequest(
        "LINE_ITEM", run.lineA, null, "5", null, "fix qty", UUID.randomUUID(), null));
    ValidationReviewDraftResult draft = draftBridge.createDraftQuote(run.runId, UUID.randomUUID());

    ValidationReviewDraftRemediationLineageDetail detail = lineageService.remediationLineage("QUOTE", draft.draftId());

    assertThat(detail.available()).isTrue();
    assertThat(detail.validationRunId()).isEqualTo(run.runId);
    assertThat(detail.correctionActionCount()).isEqualTo(1);
    assertThat(detail.remediatedDraftLineCount()).isEqualTo(1);
    ValidationReviewDraftRemediationLineageLine line = tracedLine(detail, run.lineA);
    assertThat(line.sourceLineAvailable()).isTrue();
    assertThat(line.correctionActions()).hasSize(1);
    assertThat(line.correctionActions().get(0).actionType()).isEqualTo("VALIDATION_REVIEW_LINE_ITEM_CORRECTED");
    assertThat(line.correctionActions().get(0).relatedLineItemId()).isEqualTo(run.lineA);
    assertThat(detail.externalExecution()).isEqualTo("DISABLED");
  }

  @Test
  void orderDraftWithIssueResolutionAppearsInLineLineage() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "ISS");
    ValidationIssue issue = issues.save(new ValidationIssue(tenantId, run.runId, run.extractionResultId, run.lineA, null, "MARGIN_BELOW_GUARDRAIL", "ERROR", "blocking", "{}", NOW));
    // Real 14C structured issue resolution (writes an OperatorAction on the issue id).
    reviewCommandService.resolveIssue(run.runId, issue.getId(), new ValidationIssueResolutionRequest("RESOLVED", "fixed", null, UUID.randomUUID(), null));
    ValidationReviewDraftResult draft = draftBridge.createDraftOrder(run.runId, UUID.randomUUID());

    ValidationReviewDraftRemediationLineageDetail detail = lineageService.remediationLineage("ORDER", draft.draftId());

    assertThat(detail.draftKind()).isEqualTo("ORDER");
    assertThat(detail.issueResolutionActionCount()).isEqualTo(1);
    assertThat(detail.remediatedDraftLineCount()).isEqualTo(1);
    ValidationReviewDraftRemediationLineageLine line = tracedLine(detail, run.lineA);
    assertThat(line.issueResolutionActions()).hasSize(1);
    assertThat(line.issueResolutionActions().get(0).relatedIssueId()).isEqualTo(issue.getId());
    assertThat(line.issueResolutionActions().get(0).status()).isEqualTo("RESOLVED");
  }

  @Test
  void approvalActionLinkedToDraftedLineAppearsInLineLineage() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "APPR");
    // Draft first (no open approval blocks readiness), then raise a real 14C approval on the drafted line.
    ValidationReviewDraftResult draft = draftBridge.createDraftQuote(run.runId, UUID.randomUUID());
    reviewCommandService.requestApproval(run.runId, new ValidationApprovalRequestCommand(run.lineA, "OPERATOR_CORRECTION_REVIEW", "needs sign-off", UUID.randomUUID()));

    ValidationReviewDraftRemediationLineageDetail detail = lineageService.remediationLineage("QUOTE", draft.draftId());

    assertThat(detail.approvalActionCount()).isEqualTo(1);
    ValidationReviewDraftRemediationLineageLine line = tracedLine(detail, run.lineA);
    assertThat(line.approvalActions()).hasSize(1);
    assertThat(line.approvalActions().get(0).actionType()).isEqualTo("VALIDATION_REVIEW_APPROVAL_REQUESTED");
    assertThat(line.approvalActions().get(0).relatedApprovalRequirementId()).isNotNull();
    assertThat(detail.unattachedActions()).isEmpty();
  }

  @Test
  void draftLineWithMissingSourceLineHasLimitation() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    // Review-origin draft (sourceValidationRunId set) whose line carries NO source extracted-line-item id.
    UUID runId = UUID.randomUUID();
    DraftQuote quote = draftQuotes.save(new DraftQuote(tenantId, "DQ-NOSRC", null, UUID.randomUUID(), runId, UUID.randomUUID(), "DRAFT", "USD", null, NOW));
    draftQuoteLines.save(new DraftQuoteLine(tenantId, quote.getId(), 1, "raw", "RAW", null, null, null, BigDecimal.ONE, "EA", null, null, null, null, "[]", "DRAFT", "NEEDS_REVIEW", NOW));

    ValidationReviewDraftRemediationLineageDetail detail = lineageService.remediationLineage("QUOTE", quote.getId());

    assertThat(detail.available()).isTrue();
    assertThat(detail.draftLineCount()).isEqualTo(1);
    assertThat(detail.traceableDraftLineCount()).isZero();
    assertThat(detail.limitations()).contains(ValidationReviewCommandDtos.REMEDIATION_LINEAGE_MISSING);
    assertThat(detail.limitations()).contains(ValidationReviewCommandDtos.REMEDIATION_DRAFT_LINE_SOURCE_LINE_MISSING);
    ValidationReviewDraftRemediationLineageLine line = detail.lines().get(0);
    assertThat(line.sourceLineAvailable()).isFalse();
    assertThat(line.limitations()).contains(ValidationReviewCommandDtos.REMEDIATION_DRAFT_LINE_SOURCE_LINE_MISSING);
  }

  @Test
  void runLevelApprovalAppearsAsUnattachedAction() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "RLAP");
    ValidationReviewDraftResult draft = draftBridge.createDraftQuote(run.runId, UUID.randomUUID());
    // Run-level approval (no extracted line item) — a real 14C action that maps to no drafted line.
    reviewCommandService.requestApproval(run.runId, new ValidationApprovalRequestCommand(null, "RUN_LEVEL_REVIEW", "run sign-off", UUID.randomUUID()));

    ValidationReviewDraftRemediationLineageDetail detail = lineageService.remediationLineage("QUOTE", draft.draftId());

    assertThat(detail.approvalActionCount()).isZero();
    assertThat(detail.unattachedActions()).hasSize(1);
    assertThat(detail.unattachedActions().get(0).category()).isEqualTo(ValidationReviewCommandDtos.LINEAGE_CATEGORY_APPROVAL);
    assertThat(detail.limitations()).contains(ValidationReviewCommandDtos.REMEDIATION_UNATTACHED_APPROVAL_ACTION);
  }

  @Test
  void issueResolutionOnNonDraftedLineAppearsAsUnattachedAction() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "URES");
    // Issue on a line that is NOT a drafted source line — its resolution cannot attach to a draft line.
    ValidationIssue issue = issues.save(new ValidationIssue(tenantId, run.runId, run.extractionResultId, UUID.randomUUID(), null, "DATA_QUALITY", "WARNING", "non-drafted", "{}", NOW));
    reviewCommandService.resolveIssue(run.runId, issue.getId(), new ValidationIssueResolutionRequest("RESOLVED", "noted", null, UUID.randomUUID(), null));
    ValidationReviewDraftResult draft = draftBridge.createDraftQuote(run.runId, UUID.randomUUID());

    ValidationReviewDraftRemediationLineageDetail detail = lineageService.remediationLineage("QUOTE", draft.draftId());

    assertThat(detail.issueResolutionActionCount()).isZero();
    assertThat(detail.unattachedActions()).anySatisfy(u ->
        assertThat(u.category()).isEqualTo(ValidationReviewCommandDtos.LINEAGE_CATEGORY_ISSUE_RESOLUTION));
    assertThat(detail.limitations()).contains(ValidationReviewCommandDtos.REMEDIATION_UNATTACHED_ISSUE_RESOLUTION_ACTION);
  }

  @Test
  void nonReviewOriginDraftReturnsAvailableFalse() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    // sourceValidationRunId = null -> not a review-origin draft.
    DraftQuote quote = draftQuotes.save(new DraftQuote(tenantId, "DQ-MANUAL", null, UUID.randomUUID(), null, null, "DRAFT", "USD", null, NOW));

    ValidationReviewDraftRemediationLineageDetail detail = lineageService.remediationLineage("QUOTE", quote.getId());

    assertThat(detail.available()).isFalse();
    assertThat(detail.validationRunId()).isNull();
    assertThat(detail.limitations()).contains(ValidationReviewCommandDtos.REMEDIATION_DRAFT_NOT_REVIEW_ORIGIN);
    assertThat(detail.unattachedActions()).isEmpty();
  }

  @Test
  void tenantBoundaryPreventsReadingAnotherTenantsDraftLineage() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    Run run = cleanRun(tenantA, "TA");
    ValidationReviewDraftResult draft = draftBridge.createDraftQuote(run.runId, UUID.randomUUID());

    TenantContext.setTenantId(tenantB);
    assertThatThrownBy(() -> lineageService.remediationLineage("QUOTE", draft.draftId()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void invalidDraftKindIsRejected() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    assertThatThrownBy(() -> lineageService.remediationLineage("WIDGET", UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // --- helpers -----------------------------------------------------------------------------------

  private record Run(UUID runId, UUID extractionResultId, UUID lineA) {}

  private ValidationReviewDraftRemediationLineageLine tracedLine(ValidationReviewDraftRemediationLineageDetail detail, UUID sourceLineId) {
    return detail.lines().stream()
        .filter(l -> sourceLineId.equals(l.sourceLineItemId()))
        .findFirst().orElseThrow();
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
