package com.orderpilot.application.services.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.Stage6Dtos.ReviewCaseDetail;
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
import com.orderpilot.domain.pricing.DiscountRule;
import com.orderpilot.domain.pricing.DiscountRuleRepository;
import com.orderpilot.domain.pricing.MarginRule;
import com.orderpilot.domain.pricing.MarginRuleRepository;
import com.orderpilot.domain.pricing.PriceRule;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.product.CustomerSubstitutionPreference;
import com.orderpilot.domain.product.CustomerSubstitutionPreferenceRepository;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductAlias;
import com.orderpilot.domain.product.ProductAliasRepository;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.product.ProductSubstitute;
import com.orderpilot.domain.product.ProductSubstituteRepository;
import com.orderpilot.domain.validation.ValidationRun;
import com.orderpilot.domain.workspace.ExceptionCase;
import com.orderpilot.domain.workspace.ExceptionCaseRepository;
import com.orderpilot.domain.workspace.DraftQuote;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.api.dto.Stage6Dtos.DraftPreview;
import com.orderpilot.application.services.validation.ValidationRunService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ValidationReviewBridgeStage5BTest {
  private static final Instant NOW = Instant.parse("2026-05-24T00:00:00Z");

  @Autowired private ValidationRunService validationRunService;
  @Autowired private ValidationReviewService reviewService;
  @Autowired private DraftCommandPreparationService draftPreparationService;
  @Autowired private ExtractionResultRepository extractionResults;
  @Autowired private ExtractedFieldRepository fields;
  @Autowired private ExtractedLineItemRepository lines;
  @Autowired private CustomerAccountRepository customers;
  @Autowired private ProductRepository products;
  @Autowired private ProductAliasRepository aliases;
  @Autowired private ProductSubstituteRepository substitutes;
  @Autowired private CustomerSubstitutionPreferenceRepository substitutionPreferences;
  @Autowired private LocationRepository locations;
  @Autowired private InventorySnapshotRepository inventory;
  @Autowired private PriceRuleRepository prices;
  @Autowired private DiscountRuleRepository discounts;
  @Autowired private MarginRuleRepository margins;
  @Autowired private DraftQuoteRepository draftQuotes;
  @Autowired private ExceptionCaseRepository exceptionCases;
  @Autowired private AuditEventRepository auditEvents;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void botOriginatedExceptionCaseIsNotValidationBackedReadinessCase() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ExceptionCase botCase = exceptionCases.save(new ExceptionCase(tenantId, "BOT-ACCEPTANCE", "BOT_CONVERSATION", UUID.randomUUID(), null, null, null, "Bot handoff", "OPEN", "NORMAL", "INFO", "Bot-only operator handoff", NOW));

    assertThatThrownBy(() -> reviewService.get(botCase.getId()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not validation-backed");
    assertThat(draftPreparationService.readiness(botCase).draftPreparationAllowed()).isFalse();
    assertThat(draftPreparationService.readiness(botCase).blockingReasons())
        .extracting("issueCode")
        .contains("BOT_HANDOFF_NOT_VALIDATION_BACKED");
  }

  @Test
  void validatedExtractionCanPrepareDraftQuoteInternally() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product product = product(tenantId, "SKU-READY", "Ready Filter", "10");
    Location location = location(tenantId, "READY");
    customer(tenantId, "ACME-READY", "Acme Ready", location.getId());
    inventory.save(new InventorySnapshot(tenantId, product.getId(), location.getId(), new BigDecimal("12"), new BigDecimal("12"), BigDecimal.ZERO, NOW, "TEST", null, NOW));
    prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    ExtractionResult extraction = extractionWithLine(tenantId, "SKU-READY", "Ready Filter", "2", "EA", "0.95", "{}");
    validationRunService.run(extraction.getId(), "FULL");
    ReviewCaseDetail reviewCase = reviewService.createForExtractionResult(extraction.getId());

    DraftQuote quote = draftPreparationService.prepareDraftQuote(reviewCase.reviewCase().id(), UUID.randomUUID());

    assertThat(quote.getSourceValidationRunId()).isEqualTo(reviewCase.reviewCase().validationRunId());
    assertThat(draftQuotes.findByIdAndTenantId(quote.getId(), tenantId)).isPresent();
  }

  @Test
  void unknownUomBlocksDraftPreparationUntilCorrected() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product product = product(tenantId, "SKU-UOM-BLOCK", "Uom Filter", "10");
    prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    ExtractionResult extraction = extractionWithLine(tenantId, "SKU-UOM-BLOCK", "Uom Filter", "2", "PALLET", "0.95", "{}");
    validationRunService.run(extraction.getId(), "FULL");
    ReviewCaseDetail reviewCase = reviewService.createForExtractionResult(extraction.getId());

    assertThat(reviewCase.readiness().blockingReasons()).extracting("issueCode").contains("INVALID_UOM");
    assertThat(reviewCase.readiness().pendingApprovals()).extracting("requirementType").contains("INVALID_UOM_REQUIRES_REVIEW");
    assertThatThrownBy(() -> draftPreparationService.prepareDraftQuote(reviewCase.reviewCase().id(), UUID.randomUUID()))
        .isInstanceOf(DraftPreparationBlockedException.class)
        .hasMessageContaining("hard validation issue")
        .satisfies(thrown -> assertThat(((DraftPreparationBlockedException) thrown).getBlockingReasons())
            .extracting("issueCode")
            .contains("INVALID_UOM"));
    assertThatThrownBy(() -> draftPreparationService.prepareDraftOrder(reviewCase.reviewCase().id(), UUID.randomUUID()))
        .isInstanceOf(DraftPreparationBlockedException.class)
        .satisfies(thrown -> assertThat(((DraftPreparationBlockedException) thrown).getBlockingReasons())
            .extracting("issueCode")
            .contains("INVALID_UOM"));
  }

  @Test
  void uomCorrectionIsTenantScopedAndUpdatesReviewState() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product product = product(tenantId, "SKU-UOM-CORRECT", "Correctable Uom Filter", "10");
    prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    ExtractionResult extraction = extractionWithLine(tenantId, "SKU-UOM-CORRECT", "Correctable Uom Filter", "2", "PALLET", "0.95", "{}");
    validationRunService.run(extraction.getId(), "FULL");
    ReviewCaseDetail reviewCase = reviewService.createForExtractionResult(extraction.getId());
    UUID lineId = reviewCase.lineItems().get(0).id();

    TenantContext.setTenantId(UUID.randomUUID());
    assertThatThrownBy(() -> reviewService.correctUom(reviewCase.reviewCase().id(), lineId, "EA", UUID.randomUUID()))
        .isInstanceOf(RuntimeException.class);

    TenantContext.setTenantId(tenantId);
    ReviewCaseDetail corrected = reviewService.correctUom(reviewCase.reviewCase().id(), lineId, "EA", UUID.randomUUID());

    assertThat(corrected.issueGroups().stream().flatMap(group -> group.issues().stream()).filter(issue -> "INVALID_UOM".equals(issue.issueType())).findFirst().orElseThrow().status()).isEqualTo("CORRECTED");
    assertThat(corrected.reviewCase().status()).isEqualTo("NEEDS_REVIEW_AFTER_CORRECTION");
    assertThat(corrected.correctionHistory()).extracting("actionType").contains("VALIDATION_REVIEW_UOM_CORRECTED");
    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenantId)).extracting("action").contains("VALIDATION_REVIEW_UOM_CORRECTED");
  }

  @Test
  void reviewDetailReturnsTenantScopedProductCandidatesAndBlockingState() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product first = product(tenantId, "SKU-CAND-A", "Candidate A", "10");
    Product second = product(tenantId, "SKU-CAND-B", "Candidate B", "10");
    aliases.save(new ProductAlias(tenantId, first.getId(), "CUSTOMER", "CAND", "CAND", null, new BigDecimal("0.80"), NOW));
    aliases.save(new ProductAlias(tenantId, second.getId(), "CUSTOMER", "CAND", "CAND", null, new BigDecimal("0.80"), NOW));
    ExtractionResult extraction = extractionWithLine(tenantId, "CAND", "Candidate request", "1", "EA", "0.95", "{}");
    validationRunService.run(extraction.getId(), "FULL");
    ReviewCaseDetail reviewCase = reviewService.createForExtractionResult(extraction.getId());

    assertThat(reviewCase.draftPreparationAllowed()).isFalse();
    assertThat(reviewCase.blockingReasons()).extracting("issueCode").contains("PRODUCT_AMBIGUOUS", "UNRESOLVED_PRODUCT_MATCH");
    assertThat(reviewCase.productCandidates()).extracting("productId").contains(first.getId(), second.getId());

    TenantContext.setTenantId(UUID.randomUUID());
    assertThatThrownBy(() -> reviewService.get(reviewCase.reviewCase().id())).isInstanceOf(RuntimeException.class);
  }

  @Test
  void draftPreviewShowsBlockersAndDoesNotCreateDraftOrExternalWrite() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product product = product(tenantId, "SKU-PREVIEW-BLOCK", "Preview Block Filter", "10");
    prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    ExtractionResult extraction = extractionWithLine(tenantId, "SKU-PREVIEW-BLOCK", "Preview Block Filter", "2", "PALLET", "0.95", "{}");
    validationRunService.run(extraction.getId(), "FULL");
    ReviewCaseDetail reviewCase = reviewService.createForExtractionResult(extraction.getId());
    int before = draftQuotes.findByTenantIdOrderByCreatedAtDesc(tenantId).size();

    DraftPreview preview = draftPreparationService.preview(reviewCase.reviewCase().id(), "QUOTE", UUID.randomUUID());
    ReviewCaseDetail refreshed = reviewService.get(reviewCase.reviewCase().id());

    assertThat(preview.draftPreparationAllowed()).isFalse();
    assertThat(preview.blockingReasons()).extracting("issueCode").contains("INVALID_UOM");
    assertThat(preview.lines()).hasSize(1);
    assertThat(preview.externalExecutionDisabled()).isTrue();
    assertThat(draftQuotes.findByTenantIdOrderByCreatedAtDesc(tenantId)).hasSize(before);
    assertThat(refreshed.timeline()).extracting("actionType").contains("DRAFT_PREVIEW_GENERATED");
  }

  @Test
  void resolvedBlockingIssueAllowsPreparationAfterApproval() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product product = product(tenantId, "SKU-UOM-RESOLVED", "Resolved Uom Filter", "10");
    prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    ExtractionResult extraction = extractionWithLine(tenantId, "SKU-UOM-RESOLVED", "Resolved Uom Filter", "2", "PALLET", "0.95", "{}");
    validationRunService.run(extraction.getId(), "FULL");
    ReviewCaseDetail reviewCase = reviewService.createForExtractionResult(extraction.getId());
    UUID lineId = reviewCase.lineItems().get(0).id();

    reviewService.correctUom(reviewCase.reviewCase().id(), lineId, "EA", UUID.randomUUID());
    reviewService.approveForDraft(reviewCase.reviewCase().id(), UUID.randomUUID());
    DraftQuote quote = draftPreparationService.prepareDraftQuote(reviewCase.reviewCase().id(), UUID.randomUUID());

    assertThat(quote.getSourceValidationRunId()).isEqualTo(reviewCase.reviewCase().validationRunId());
  }

  @Test
  void riskyOverrideRequiresReasonAndEmitsAuditEvent() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product product = product(tenantId, "SKU-OVERRIDE", "Override Filter", "10");
    prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    ExtractionResult extraction = extractionWithLine(tenantId, "SKU-OVERRIDE", "Override Filter", "2", "PALLET", "0.95", "{}");
    validationRunService.run(extraction.getId(), "FULL");
    ReviewCaseDetail reviewCase = reviewService.createForExtractionResult(extraction.getId());
    UUID issueId = reviewCase.issueGroups().stream().flatMap(group -> group.issues().stream()).filter(issue -> "INVALID_UOM".equals(issue.issueType())).findFirst().orElseThrow().id();

    assertThatThrownBy(() -> reviewService.overrideIssue(reviewCase.reviewCase().id(), issueId, UUID.randomUUID(), " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Override reason");

    ReviewCaseDetail overridden = reviewService.overrideIssue(reviewCase.reviewCase().id(), issueId, UUID.randomUUID(), "customer confirmed case pack");

    assertThat(overridden.issueGroups().stream().flatMap(group -> group.issues().stream()).filter(issue -> issue.id().equals(issueId)).findFirst().orElseThrow().status()).isEqualTo("OVERRIDDEN");
    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenantId)).extracting("action").contains("VALIDATION_REVIEW_ISSUE_OVERRIDDEN");
  }

  @Test
  void blockedSubstituteCannotBePreparedEvenAfterReviewApproval() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product source = product(tenantId, "SKU-BLOCK-SRC", "Source", "10");
    Product substitute = product(tenantId, "SKU-BLOCK-SUB", "Blocked Substitute", "12");
    substitutes.save(new ProductSubstitute(tenantId, source.getId(), substitute.getId(), "FUNCTIONAL", "LOW", false, "blocked customer preference", NOW));
    Location location = location(tenantId, "BLOCKED");
    CustomerAccount customer = customer(tenantId, "ACME-BLOCK", "Acme Block", location.getId());
    substitutionPreferences.save(new CustomerSubstitutionPreference(tenantId, customer.getId(), source.getId(), null, true, true, substitute.getId(), "blocked", NOW));
    inventory.save(new InventorySnapshot(tenantId, source.getId(), location.getId(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, NOW, "TEST", null, NOW));
    inventory.save(new InventorySnapshot(tenantId, substitute.getId(), location.getId(), new BigDecimal("8"), new BigDecimal("8"), BigDecimal.ZERO, NOW, "TEST", null, NOW));
    prices.save(new PriceRule(tenantId, source.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    ExtractionResult extraction = extractionResults.save(new ExtractionResult(tenantId, UUID.randomUUID(), "CHANNEL_MESSAGE", UUID.randomUUID(), "RFQ", "message", new BigDecimal("0.95"), "{}", "ready_for_validation", NOW));
    fields.save(new ExtractedField(tenantId, extraction.getId(), "customer_hint", "Acme Block", "Acme Block", "customer_hint", new BigDecimal("0.95"), null, NOW));
    lines.save(new ExtractedLineItem(tenantId, extraction.getId(), 1, "SKU-BLOCK-SRC", "Source", "2", new BigDecimal("2"), "EA", "EA", new BigDecimal("0.95"), null, NOW));
    validationRunService.run(extraction.getId(), "FULL");
    ReviewCaseDetail reviewCase = reviewService.createForExtractionResult(extraction.getId());
    reviewService.approveForDraft(reviewCase.reviewCase().id(), UUID.randomUUID());

    assertThatThrownBy(() -> draftPreparationService.prepareDraftQuote(reviewCase.reviewCase().id(), UUID.randomUUID()))
        .isInstanceOf(DraftPreparationBlockedException.class)
        .hasMessageContaining("Blocked substitute");

    UUID candidateId = reviewService.get(reviewCase.reviewCase().id()).substituteCandidates().get(0).id();
    assertThatThrownBy(() -> reviewService.selectSubstitute(reviewCase.reviewCase().id(), candidateId, UUID.randomUUID(), "try blocked"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Blocked substitute");
    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenantId)).extracting("action").contains("VALIDATION_REVIEW_BLOCKED_SUBSTITUTE_SELECTION_REJECTED");
  }

  @Test
  void marginAndDiscountViolationBlocksDraftPreparationBeforeApproval() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product product = product(tenantId, "SKU-MARGIN-BLOCK", "Margin Risk", "95");
    prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("100"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    discounts.save(new DiscountRule(tenantId, "DISC", "Max discount", null, null, product.getId(), new BigDecimal("5"), new BigDecimal("5"), NOW.minusSeconds(60), null, NOW));
    margins.save(new MarginRule(tenantId, "MARGIN", "Margin floor", product.getId(), null, null, new BigDecimal("20"), new BigDecimal("30"), NOW));
    ExtractionResult extraction = extractionWithLine(tenantId, "SKU-MARGIN-BLOCK", "Margin Risk", "2", "EA", "0.95", "{\"discountPercent\":\"20\"}");
    validationRunService.run(extraction.getId(), "FULL");
    ReviewCaseDetail reviewCase = reviewService.createForExtractionResult(extraction.getId());

    assertThat(reviewCase.readiness().blockingReasons()).extracting("issueCode").contains("DISCOUNT_REQUIRES_APPROVAL", "MARGIN_BELOW_GUARDRAIL");
    assertThat(reviewCase.readiness().pendingApprovals()).extracting("requirementType").contains("DISCOUNT_REQUIRES_APPROVAL", "MARGIN_BELOW_GUARDRAIL");
    assertThatThrownBy(() -> draftPreparationService.prepareDraftQuote(reviewCase.reviewCase().id(), UUID.randomUUID()))
        .isInstanceOf(DraftPreparationBlockedException.class)
        .hasMessageContaining("Approval-backed validation issue")
        .satisfies(thrown -> assertThat(((DraftPreparationBlockedException) thrown).getBlockingReasons())
            .extracting("issueCode")
            .contains("DISCOUNT_REQUIRES_APPROVAL", "MARGIN_BELOW_GUARDRAIL"));
  }

  @Test
  void managerApprovalDecisionControlsReadinessAndPreparation() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product product = product(tenantId, "SKU-MANAGER-APPROVAL", "Manager Approval Risk", "75");
    prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("100"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    discounts.save(new DiscountRule(tenantId, "DISC-MGR", "Max discount", null, null, product.getId(), new BigDecimal("5"), new BigDecimal("5"), NOW.minusSeconds(60), null, NOW));
    margins.save(new MarginRule(tenantId, "MARGIN-MGR", "Margin floor", product.getId(), null, null, new BigDecimal("20"), new BigDecimal("30"), NOW));
    ExtractionResult extraction = extractionWithLine(tenantId, "SKU-MANAGER-APPROVAL", "Manager Approval Risk", "2", "EA", "0.95", "{\"discountPercent\":\"20\"}");
    validationRunService.run(extraction.getId(), "FULL");
    ReviewCaseDetail reviewCase = reviewService.createForExtractionResult(extraction.getId());

    assertThat(reviewCase.readiness().draftPreparationAllowed()).isFalse();
    assertThat(reviewCase.readiness().pendingApprovals()).extracting("requirementType").contains("DISCOUNT_REQUIRES_APPROVAL", "MARGIN_BELOW_GUARDRAIL");
    assertThatThrownBy(() -> draftPreparationService.prepareDraftQuote(reviewCase.reviewCase().id(), UUID.randomUUID()))
        .isInstanceOf(DraftPreparationBlockedException.class)
        .hasMessageContaining("Manager approval is pending");

    UUID actorId = UUID.randomUUID();
    for (var approval : reviewCase.approvalRequirements()) {
      reviewService.approveApproval(reviewCase.reviewCase().id(), approval.id(), actorId, "manager accepted controlled risk");
    }
    reviewService.approveForDraft(reviewCase.reviewCase().id(), actorId);
    ReviewCaseDetail approved = reviewService.get(reviewCase.reviewCase().id());

    assertThat(approved.readiness().draftPreparationAllowed()).isTrue();
    assertThat(approved.readiness().resolvedApprovals()).extracting("status").contains("APPROVED");
    assertThat(draftPreparationService.prepareDraftQuote(reviewCase.reviewCase().id(), actorId).getSourceValidationRunId()).isEqualTo(reviewCase.reviewCase().validationRunId());
    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenantId)).extracting("action").contains("VALIDATION_REVIEW_APPROVAL_APPROVED");
  }

  @Test
  void rejectedRequiredApprovalBlocksDraftPreparationAndPreviewUsesSameReadiness() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product product = product(tenantId, "SKU-REJECTED-APPROVAL", "Rejected Approval Risk", "75");
    prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("100"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    discounts.save(new DiscountRule(tenantId, "DISC-REJECT", "Max discount", null, null, product.getId(), new BigDecimal("5"), new BigDecimal("5"), NOW.minusSeconds(60), null, NOW));
    ExtractionResult extraction = extractionWithLine(tenantId, "SKU-REJECTED-APPROVAL", "Rejected Approval Risk", "2", "EA", "0.95", "{\"discountPercent\":\"20\"}");
    validationRunService.run(extraction.getId(), "FULL");
    ReviewCaseDetail reviewCase = reviewService.createForExtractionResult(extraction.getId());
    UUID approvalId = reviewCase.approvalRequirements().get(0).id();

    assertThatThrownBy(() -> reviewService.rejectApproval(reviewCase.reviewCase().id(), approvalId, UUID.randomUUID(), " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Rejection reason");

    ReviewCaseDetail rejected = reviewService.rejectApproval(reviewCase.reviewCase().id(), approvalId, UUID.randomUUID(), "discount is not acceptable");
    DraftPreview preview = draftPreparationService.preview(reviewCase.reviewCase().id(), "QUOTE", UUID.randomUUID());

    assertThat(rejected.readiness().rejectedApprovals()).extracting("id").contains(approvalId);
    assertThat(preview.readiness().blockingReasons()).extracting("issueCode").containsExactlyElementsOf(rejected.readiness().blockingReasons().stream().map(reason -> reason.issueCode()).toList());
    assertThatThrownBy(() -> draftPreparationService.prepareDraftQuote(reviewCase.reviewCase().id(), UUID.randomUUID()))
        .isInstanceOf(DraftPreparationBlockedException.class)
        .hasMessageContaining("Required approval was rejected");
  }

  @Test
  void approvalDecisionIsTenantScoped() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product product = product(tenantId, "SKU-APPROVAL-TENANT", "Approval Tenant Risk", "75");
    prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("100"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    discounts.save(new DiscountRule(tenantId, "DISC-TENANT", "Max discount", null, null, product.getId(), new BigDecimal("5"), new BigDecimal("5"), NOW.minusSeconds(60), null, NOW));
    ExtractionResult extraction = extractionWithLine(tenantId, "SKU-APPROVAL-TENANT", "Approval Tenant Risk", "2", "EA", "0.95", "{\"discountPercent\":\"20\"}");
    validationRunService.run(extraction.getId(), "FULL");
    ReviewCaseDetail reviewCase = reviewService.createForExtractionResult(extraction.getId());
    UUID approvalId = reviewCase.approvalRequirements().get(0).id();

    TenantContext.setTenantId(UUID.randomUUID());

    assertThatThrownBy(() -> reviewService.approveApproval(reviewCase.reviewCase().id(), approvalId, UUID.randomUUID(), "wrong tenant"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void riskySubstituteRequiresApprovalBeforePreparation() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product source = product(tenantId, "SKU-RISK-SRC", "Risk Source", "10");
    Product substitute = product(tenantId, "SKU-RISK-SUB", "Risk Substitute", "12");
    substitutes.save(new ProductSubstitute(tenantId, source.getId(), substitute.getId(), "FUNCTIONAL", "HIGH", true, "high risk substitute", NOW));
    Location location = location(tenantId, "RISK");
    customer(tenantId, "ACME-RISK", "Acme Risk", location.getId());
    inventory.save(new InventorySnapshot(tenantId, source.getId(), location.getId(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, NOW, "TEST", null, NOW));
    inventory.save(new InventorySnapshot(tenantId, substitute.getId(), location.getId(), new BigDecimal("8"), new BigDecimal("8"), BigDecimal.ZERO, NOW, "TEST", null, NOW));
    prices.save(new PriceRule(tenantId, source.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    ExtractionResult extraction = extractionWithLine(tenantId, "SKU-RISK-SRC", "Risk Source", "2", "EA", "0.95", "{}");
    validationRunService.run(extraction.getId(), "FULL");
    ReviewCaseDetail reviewCase = reviewService.createForExtractionResult(extraction.getId());
    UUID candidateId = reviewCase.substituteCandidates().get(0).id();
    UUID approvalId = reviewCase.approvalRequirements().stream().filter(approval -> "SUBSTITUTE_REQUIRES_APPROVAL".equals(approval.requirementType())).findFirst().orElseThrow().id();

    ReviewCaseDetail selected = reviewService.selectSubstitute(reviewCase.reviewCase().id(), candidateId, UUID.randomUUID(), "operator selected high-risk substitute");
    reviewService.approveForDraft(reviewCase.reviewCase().id(), UUID.randomUUID());

    assertThat(selected.readiness().pendingApprovals()).extracting("id").contains(approvalId);
    assertThat(reviewService.get(reviewCase.reviewCase().id()).readiness().blockingReasons()).extracting("issueCode").contains("SUBSTITUTE_REQUIRES_APPROVAL");
    assertThatThrownBy(() -> draftPreparationService.prepareDraftQuote(reviewCase.reviewCase().id(), UUID.randomUUID()))
        .isInstanceOf(DraftPreparationBlockedException.class)
        .hasMessageContaining("Selected risky substitute")
        .satisfies(thrown -> assertThat(((DraftPreparationBlockedException) thrown).getBlockingReasons())
            .extracting("issueCode")
            .contains("SUBSTITUTE_REQUIRES_APPROVAL"));

    reviewService.approveApproval(reviewCase.reviewCase().id(), approvalId, UUID.randomUUID(), "manager accepted substitute risk");
    reviewService.approveForDraft(reviewCase.reviewCase().id(), UUID.randomUUID());

    assertThat(draftPreparationService.prepareDraftQuote(reviewCase.reviewCase().id(), UUID.randomUUID()).getSourceValidationRunId()).isEqualTo(reviewCase.reviewCase().validationRunId());
  }

  @Test
  void tenantCannotPrepareAnotherTenantReviewCase() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    Product product = product(tenantA, "SKU-TENANT", "Tenant Filter", "10");
    prices.save(new PriceRule(tenantA, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    ExtractionResult extraction = extractionWithLine(tenantA, "SKU-TENANT", "Tenant Filter", "2", "EA", "0.95", "{}");
    validationRunService.run(extraction.getId(), "FULL");
    UUID reviewCaseId = reviewService.createForExtractionResult(extraction.getId()).reviewCase().id();

    TenantContext.setTenantId(UUID.randomUUID());

    assertThatThrownBy(() -> draftPreparationService.prepareDraftQuote(reviewCaseId, UUID.randomUUID()))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void reviewCaseListIsTenantScoped() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    Product productA = product(tenantA, "SKU-LIST-A", "Tenant A Filter", "10");
    prices.save(new PriceRule(tenantA, productA.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    ExtractionResult extractionA = extractionWithLine(tenantA, "SKU-LIST-A", "Tenant A Filter", "2", "EA", "0.95", "{}");
    validationRunService.run(extractionA.getId(), "FULL");
    UUID reviewCaseA = reviewService.createForExtractionResult(extractionA.getId()).reviewCase().id();

    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantB);
    Product productB = product(tenantB, "SKU-LIST-B", "Tenant B Filter", "10");
    prices.save(new PriceRule(tenantB, productB.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    ExtractionResult extractionB = extractionWithLine(tenantB, "SKU-LIST-B", "Tenant B Filter", "2", "EA", "0.95", "{}");
    validationRunService.run(extractionB.getId(), "FULL");
    UUID reviewCaseB = reviewService.createForExtractionResult(extractionB.getId()).reviewCase().id();

    assertThat(reviewService.list()).extracting("id").contains(reviewCaseB).doesNotContain(reviewCaseA);
  }

  @Test
  void approvalAndDraftPreparationEmitAuditEvents() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product product = product(tenantId, "SKU-AUDIT", "Audit Filter", "10");
    prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    ExtractionResult extraction = extractionWithLine(tenantId, "SKU-AUDIT", "Audit Filter", "2", "EA", "0.95", "{}");
    validationRunService.run(extraction.getId(), "FULL");
    ReviewCaseDetail reviewCase = reviewService.createForExtractionResult(extraction.getId());
    UUID actorId = UUID.randomUUID();

    reviewService.approveForDraft(reviewCase.reviewCase().id(), actorId);
    draftPreparationService.prepareDraftQuote(reviewCase.reviewCase().id(), actorId);

    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenantId))
        .extracting("action")
        .contains("VALIDATION_REVIEW_APPROVED_FOR_DRAFT", "DRAFT_QUOTE_PREPARATION_SUCCEEDED");
  }

  private ExtractionResult extractionWithLine(UUID tenantId, String sku, String description, String quantity, String uom, String confidence, String resultJson) {
    customer(tenantId, "ACME-" + sku, "Acme " + sku, null);
    ExtractionResult result = extractionResults.save(new ExtractionResult(tenantId, UUID.randomUUID(), "CHANNEL_MESSAGE", UUID.randomUUID(), "RFQ", "message", new BigDecimal(confidence), resultJson, new BigDecimal(confidence).compareTo(new BigDecimal("0.50")) < 0 ? "needs_review" : "ready_for_validation", NOW));
    fields.save(new ExtractedField(tenantId, result.getId(), "customer_hint", "Acme " + sku, "Acme " + sku, "customer_hint", new BigDecimal(confidence), null, NOW));
    lines.save(new ExtractedLineItem(tenantId, result.getId(), 1, sku, description, quantity, new BigDecimal(quantity), uom, uom, new BigDecimal(confidence), null, NOW));
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
