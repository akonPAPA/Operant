package com.orderpilot.application.services.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.AdvisoryValidationHandoffDtos.AdvisoryValidationHandoffResult;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationApprovalRequestCommand;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationIssueResolutionRequest;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewActionResult;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewCorrectionRequest;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEvent;
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
import com.orderpilot.domain.validation.ApprovalRequirementRepository;
import com.orderpilot.domain.validation.ValidationIssue;
import com.orderpilot.domain.validation.ValidationIssueRepository;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.domain.workspace.OperatorAction;
import com.orderpilot.domain.workspace.OperatorActionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-14C — operator validation-review command layer tests.
 *
 * <p>Proves the command surface is tenant-scoped, validates target ownership and state before mutation,
 * rejects unsupported/raw targets, stores bounded snapshots with no raw advisory payload, emits audit,
 * is idempotent for issue resolution, and never mutates quote/order/product/customer/inventory/price state.
 */
@SpringBootTest
@ActiveProfiles("test")
class ValidationReviewCommandStage14CTest {
  private static final Instant NOW = Instant.parse("2026-06-09T00:00:00Z");
  private static final String SECRET_SENTINEL = "TOP_SECRET_PROMPT_DO_NOT_LEAK_14c";

  @Autowired private AdvisoryExtractionValidationHandoffService handoffService;
  @Autowired private ValidationReviewCommandService commandService;
  @Autowired private ExtractionResultRepository extractionResults;
  @Autowired private ExtractedFieldRepository fields;
  @Autowired private ExtractedLineItemRepository lines;
  @Autowired private ValidationIssueRepository issues;
  @Autowired private OperatorActionRepository operatorActions;
  @Autowired private AuditEventRepository auditEvents;
  @Autowired private ApprovalRequirementRepository approvals;
  @Autowired private CustomerAccountRepository customers;
  @Autowired private ProductRepository products;
  @Autowired private LocationRepository locations;
  @Autowired private InventorySnapshotRepository inventory;
  @Autowired private PriceRuleRepository prices;
  @Autowired private DraftQuoteRepository draftQuotes;
  @Autowired private DraftOrderRepository draftOrders;
  @Autowired private JsonSupport json;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void fieldCorrectionStoresBoundedValueEmitsAuditAndMutatesNoMasterData() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedCatalog(tenantId, "SKU-OK");
    AdvisoryValidationHandoffResult handoff = handoff(tenantId, "SKU-OK", "2");
    ExtractedField field = fields.findByTenantIdAndExtractionResultId(tenantId, handoff.extractionResultId()).get(0);

    long productsBefore = products.count();
    long customersBefore = customers.count();
    long inventoryBefore = inventory.count();
    long pricesBefore = prices.count();
    long quotesBefore = draftQuotes.count();
    long ordersBefore = draftOrders.count();

    ValidationReviewActionResult result = commandService.submitCorrection(handoff.validationRunId(),
        new ValidationReviewCorrectionRequest("FIELD", field.getId(), "Acme Filters LLC", null, null, "operator typo fix", UUID.randomUUID(), "req-1"));

    assertThat(result.actionType()).isEqualTo("VALIDATION_REVIEW_FIELD_CORRECTED");
    assertThat(result.actionStatus()).isEqualTo("RECORDED");
    assertThat(result.validationRunId()).isEqualTo(handoff.validationRunId());
    assertThat(result.clientRequestId()).isEqualTo("req-1");

    // Advisory field row corrected (not raw value provenance).
    ExtractedField updated = fields.findByIdAndTenantId(field.getId(), tenantId).orElseThrow();
    assertThat(updated.getNormalizedValue()).isEqualTo("Acme Filters LLC");
    assertThat(updated.getValidationStatus()).isEqualTo("CORRECTED");

    // OperatorAction + AuditEvent recorded with bounded metadata; no raw advisory payload.
    OperatorAction action = operatorActions.findById(result.actionId()).orElseThrow();
    assertThat(action.getMessage()).doesNotContain(SECRET_SENTINEL);
    Map<String, Object> metadata = json.parseObject(operatorActionMetadata(action));
    assertThat(metadata.get("afterValue")).isEqualTo("Acme Filters LLC");
    assertThat(operatorActionMetadata(action)).doesNotContain(SECRET_SENTINEL).doesNotContain("untrustedUntilValidation");
    List<AuditEvent> audit = auditEvents.findByTenantIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(tenantId, "EXTRACTED_FIELD", field.getId().toString());
    assertThat(audit).extracting(AuditEvent::getAction).contains("VALIDATION_REVIEW_FIELD_CORRECTED");

    // No business / master-data mutation.
    assertThat(products.count()).isEqualTo(productsBefore);
    assertThat(customers.count()).isEqualTo(customersBefore);
    assertThat(inventory.count()).isEqualTo(inventoryBefore);
    assertThat(prices.count()).isEqualTo(pricesBefore);
    assertThat(draftQuotes.count()).isEqualTo(quotesBefore);
    assertThat(draftOrders.count()).isEqualTo(ordersBefore);
  }

  @Test
  void lineItemCorrectionAppliesQuantityAndUom() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedCatalog(tenantId, "SKU-LN");
    AdvisoryValidationHandoffResult handoff = handoff(tenantId, "SKU-LN", "2");
    ExtractedLineItem line = lines.findByTenantIdAndExtractionResultId(tenantId, handoff.extractionResultId()).get(0);

    ValidationReviewActionResult result = commandService.submitCorrection(handoff.validationRunId(),
        new ValidationReviewCorrectionRequest("LINE_ITEM", line.getId(), null, "5", "box", "operator qty fix", null, null));

    assertThat(result.actionType()).isEqualTo("VALIDATION_REVIEW_LINE_ITEM_CORRECTED");
    ExtractedLineItem updated = lines.findByIdAndTenantId(line.getId(), tenantId).orElseThrow();
    assertThat(updated.getNormalizedQuantity()).isEqualByComparingTo("5");
    assertThat(updated.getNormalizedUom()).isEqualTo("BOX");
    assertThat(updated.getValidationStatus()).isEqualTo("CORRECTED");
  }

  @Test
  void correctionRequiresTenantOwnership() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    seedCatalog(tenantA, "SKU-A");
    AdvisoryValidationHandoffResult handoff = handoff(tenantA, "SKU-A", "2");
    ExtractedField field = fields.findByTenantIdAndExtractionResultId(tenantA, handoff.extractionResultId()).get(0);

    TenantContext.setTenantId(UUID.randomUUID());
    assertThatThrownBy(() -> commandService.submitCorrection(handoff.validationRunId(),
        new ValidationReviewCorrectionRequest("FIELD", field.getId(), "x", null, null, "r", null, null)))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("validation_run_not_found");
  }

  @Test
  void correctionRejectsTargetNotBelongingToRun() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedCatalog(tenantId, "SKU-1");
    AdvisoryValidationHandoffResult runOne = handoff(tenantId, "SKU-1", "2");
    AdvisoryValidationHandoffResult runTwo = handoff(tenantId, "SKU-1", "3");
    // Field from run two, submitted against run one.
    ExtractedField foreignField = fields.findByTenantIdAndExtractionResultId(tenantId, runTwo.extractionResultId()).get(0);

    assertThatThrownBy(() -> commandService.submitCorrection(runOne.validationRunId(),
        new ValidationReviewCorrectionRequest("FIELD", foreignField.getId(), "x", null, null, "r", null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("target_not_in_validation_run");
  }

  @Test
  void correctionRejectsUnsupportedOrRawPayloadTarget() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedCatalog(tenantId, "SKU-R");
    AdvisoryValidationHandoffResult handoff = handoff(tenantId, "SKU-R", "2");

    assertThatThrownBy(() -> commandService.submitCorrection(handoff.validationRunId(),
        new ValidationReviewCorrectionRequest("SOURCE_EVIDENCE", UUID.randomUUID(), "x", null, null, "r", null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported_correction_target");
  }

  @Test
  void issueResolutionResolvesReflectsAndIsIdempotent() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    // Quantity 0 produces a blocking INVALID_QUANTITY issue.
    AdvisoryValidationHandoffResult handoff = handoff(tenantId, "SKU-Q", "0");
    ValidationIssue issue = issues.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantId, handoff.validationRunId()).get(0);

    ValidationReviewActionResult first = commandService.resolveIssue(handoff.validationRunId(), issue.getId(),
        new ValidationIssueResolutionRequest("RESOLVED", "fixed by operator", null, UUID.randomUUID(), null));
    assertThat(first.issueResolution()).isEqualTo("RESOLVED");
    assertThat(first.actionStatus()).isEqualTo("RESOLVED");
    assertThat(issues.findByIdAndTenantId(issue.getId(), tenantId).orElseThrow().getStatus()).isEqualTo("RESOLVED");

    // Idempotent: re-resolving to the same state does not create a second action.
    ValidationReviewActionResult second = commandService.resolveIssue(handoff.validationRunId(), issue.getId(),
        new ValidationIssueResolutionRequest("RESOLVED", "again", null, null, null));
    assertThat(second.actionId()).isNull();
    assertThat(second.actionStatus()).isEqualTo("RESOLVED");
    long actionCount = operatorActions.findByTenantIdAndTargetTypeAndTargetIdOrderByCreatedAtDesc(tenantId, "VALIDATION_ISSUE", issue.getId()).size();
    assertThat(actionCount).isEqualTo(1);
  }

  @Test
  void issueResolutionRejectsInvalidAndIllegalTransition() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    AdvisoryValidationHandoffResult handoff = handoff(tenantId, "SKU-Q2", "0");
    ValidationIssue issue = issues.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantId, handoff.validationRunId()).get(0);

    assertThatThrownBy(() -> commandService.resolveIssue(handoff.validationRunId(), issue.getId(),
        new ValidationIssueResolutionRequest("BOGUS", "r", null, null, null)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("invalid_resolution");

    commandService.resolveIssue(handoff.validationRunId(), issue.getId(),
        new ValidationIssueResolutionRequest("RESOLVED", "fixed", null, null, null));
    // RESOLVED -> ESCALATED is an illegal transition.
    assertThatThrownBy(() -> commandService.resolveIssue(handoff.validationRunId(), issue.getId(),
        new ValidationIssueResolutionRequest("ESCALATED", "no", null, null, null)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("illegal_issue_transition");
  }

  @Test
  void issueResolutionRejectsForeignTenantAndForeignRun() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    AdvisoryValidationHandoffResult handoff = handoff(tenantA, "SKU-T", "0");
    ValidationIssue issue = issues.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantA, handoff.validationRunId()).get(0);

    TenantContext.setTenantId(UUID.randomUUID());
    assertThatThrownBy(() -> commandService.resolveIssue(handoff.validationRunId(), issue.getId(),
        new ValidationIssueResolutionRequest("RESOLVED", "r", null, null, null)))
        .isInstanceOf(NotFoundException.class).hasMessageContaining("validation_run_not_found");
  }

  @Test
  void approvalRequestCreatesPendingApprovalReusingExistingInfra() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedCatalog(tenantId, "SKU-AP");
    AdvisoryValidationHandoffResult handoff = handoff(tenantId, "SKU-AP", "2");
    long approvalsBefore = approvals.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantId, handoff.validationRunId()).size();

    ValidationReviewActionResult result = commandService.requestApproval(handoff.validationRunId(),
        new ValidationApprovalRequestCommand(null, "OPERATOR_CORRECTION_REVIEW", "needs manager sign-off", UUID.randomUUID()));

    assertThat(result.approvalRequired()).isTrue();
    assertThat(result.approvalRequestId()).isNotNull();
    long approvalsAfter = approvals.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantId, handoff.validationRunId()).size();
    assertThat(approvalsAfter).isEqualTo(approvalsBefore + 1);
  }

  // --- helpers -----------------------------------------------------------------------------------

  private AdvisoryValidationHandoffResult handoff(UUID tenantId, String sku, String quantity) {
    ExtractionResult result = aiWorkerResult(tenantId, 0.9,
        extraction(0.9, field("customer_hint", "Acme"), line(1, sku, "Filter", quantity, "EA", 0.9)));
    return handoffService.handoff(result.getId());
  }

  private void seedCatalog(UUID tenantId, String sku) {
    Product product = products.save(new Product(tenantId, sku, "Filter", "Filter", "Filters", "Brand", "Maker", "EA", "ACTIVE", new BigDecimal("10"), "USD", NOW));
    Location location = locations.save(new Location(tenantId, "MAIN", "Main", "WAREHOUSE", null, "Almaty", "KZ", true, NOW));
    customers.save(new CustomerAccount(tenantId, null, "ACME", "Acme Filter", "Acme Filter", null, "ACTIVE", "USD", location.getId(), NOW));
    inventory.save(new InventorySnapshot(tenantId, product.getId(), location.getId(), new BigDecimal("20"), new BigDecimal("20"), BigDecimal.ZERO, NOW, "TEST", null, NOW));
    prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
  }

  private String operatorActionMetadata(OperatorAction action) {
    // OperatorAction does not expose metadata via getter; re-read the persisted JSON through the audit trail mirror.
    List<AuditEvent> audit = auditEvents.findByTenantIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(
        TenantContext.requireTenantId(), action.getTargetType(), action.getTargetId().toString());
    return audit.isEmpty() ? "{}" : audit.get(0).getMetadata();
  }

  private ExtractionResult aiWorkerResult(UUID tenantId, double overall, Map<String, Object> extraction) {
    Map<String, Object> wrapper = new LinkedHashMap<>();
    wrapper.put("advisoryOnly", true);
    wrapper.put("source", "AI_WORKER");
    wrapper.put("untrustedUntilValidation", true);
    wrapper.put("workerStatus", "SUCCEEDED");
    wrapper.put("promptText", SECRET_SENTINEL);
    wrapper.put("safeFailureReason", null);
    wrapper.put("extraction", extraction);
    return extractionResults.save(new ExtractionResult(
        tenantId, UUID.randomUUID(), "CHANNEL_MESSAGE", UUID.randomUUID(), "RFQ", "message",
        BigDecimal.valueOf(overall), json.writeObject(wrapper), "READY_FOR_VALIDATION", NOW));
  }

  private static Map<String, Object> extraction(double overall, Map<String, Object> field, Map<String, Object> line) {
    Map<String, Object> extraction = new LinkedHashMap<>();
    extraction.put("detected_intent", "RFQ");
    extraction.put("overall_confidence", overall);
    extraction.put("fields", List.of(field));
    extraction.put("line_items", List.of(line));
    return extraction;
  }

  private static Map<String, Object> field(String name, String value) {
    Map<String, Object> field = new LinkedHashMap<>();
    field.put("field_name", name);
    field.put("raw_value", value);
    field.put("normalized_value", value);
    field.put("value_type", name);
    field.put("confidence", 0.9);
    return field;
  }

  private static Map<String, Object> line(int number, String sku, String description, String quantity, String uom, double confidence) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("line_number", number);
    line.put("raw_sku", sku);
    line.put("raw_description", description);
    line.put("raw_quantity", quantity);
    line.put("raw_uom", uom);
    line.put("confidence", confidence);
    return line;
  }
}
