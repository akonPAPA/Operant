package com.orderpilot.application.services.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.AdvisoryValidationHandoffDtos.AdvisoryValidationHandoffResult;
import com.orderpilot.api.dto.ValidationReviewDtos;
import com.orderpilot.api.dto.ValidationReviewDtos.AllowedReviewAction;
import com.orderpilot.api.dto.ValidationReviewDtos.SourceEvidenceReviewItem;
import com.orderpilot.api.dto.ValidationReviewDtos.ValidationIssueReviewItem;
import com.orderpilot.api.dto.ValidationReviewDtos.ValidationReviewDetailResponse;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.extraction.ExtractionResult;
import com.orderpilot.domain.inventory.InventorySnapshot;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.location.Location;
import com.orderpilot.domain.location.LocationRepository;
import com.orderpilot.domain.pricing.PriceRule;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
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
 * OP-CAP-14A — read-only validation review API surface / operator result contract.
 *
 * <p>Proves that the bounded {@link ValidationReviewDetailResponse} composed by
 * {@link ValidationReviewQueryService} faithfully reflects the deterministic validation artifacts
 * produced by the OP-CAP-13A/13B advisory handoff, enforces tenant isolation, fails closed on missing
 * or foreign resources, and never leaks the raw AI advisory payload, prompt text, or secrets. The
 * contract is read-only and creates no business records.
 */
@SpringBootTest
@ActiveProfiles("test")
class ValidationReviewQueryStage14ATest {
  private static final Instant NOW = Instant.parse("2026-06-09T00:00:00Z");
  private static final String SECRET_SENTINEL = "TOP_SECRET_PROMPT_DO_NOT_LEAK_9c1f";

  @Autowired private AdvisoryExtractionValidationHandoffService handoffService;
  @Autowired private ValidationReviewQueryService reviewQueryService;
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
  void reviewByValidationRunReturnsExtractionValidationFieldsLinesIssues() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedCatalog(tenantId, "SKU-OK", new BigDecimal("20"));
    ExtractionResult result = aiWorkerResult(tenantId, "SUCCEEDED", "READY_FOR_VALIDATION", 0.9,
        extraction(0.9, field("customer_hint", "Acme Filter"), line(1, "SKU-OK", "Filter", "2", "EA", 0.9)));
    AdvisoryValidationHandoffResult handoff = handoffService.handoff(result.getId());

    ValidationReviewDetailResponse review = reviewQueryService.reviewByValidationRun(handoff.validationRunId());

    assertThat(review.advisoryOnly()).isTrue();
    assertThat(review.extraction().advisoryOnly()).isTrue();
    assertThat(review.extraction().extractionResultId()).isEqualTo(result.getId());
    assertThat(review.extraction().sourceType()).isEqualTo(result.getSourceType());
    assertThat(review.extraction().workerStatus()).isEqualTo("SUCCEEDED");
    assertThat(review.validationRun().validationRunId()).isEqualTo(handoff.validationRunId());
    assertThat(review.validationRun().overallStatus()).isNotBlank();
    assertThat(review.validationRun().routingDecision()).isNotBlank();
    assertThat(review.fields()).extracting(f -> f.fieldName()).contains("customer_hint");
    assertThat(review.lineItems()).hasSize(1);
    assertThat(review.lineItems().get(0).rawSku()).isEqualTo("SKU-OK");
    assertThat(review.lineItems().get(0).quantity()).isEqualByComparingTo("2");
    // Declarative next-action hints are present and bounded; none is an executable command.
    assertThat(review.allowedActions()).extracting(AllowedReviewAction::action)
        .contains(ValidationReviewDtos.ACTION_REVIEW_FIELDS, ValidationReviewDtos.ACTION_RERUN_VALIDATION,
            ValidationReviewDtos.ACTION_CREATE_DRAFT_QUOTE);
    assertThat(actionByName(review, ValidationReviewDtos.ACTION_CREATE_DRAFT_QUOTE).enabled()).isFalse();
  }

  @Test
  void reviewByExtractionResultReturnsLatestRun() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedCatalog(tenantId, "SKU-EX", new BigDecimal("20"));
    ExtractionResult result = aiWorkerResult(tenantId, "SUCCEEDED", "READY_FOR_VALIDATION", 0.9,
        extraction(0.9, field("customer_hint", "Acme"), line(1, "SKU-EX", "Filter", "2", "EA", 0.9)));
    AdvisoryValidationHandoffResult handoff = handoffService.handoff(result.getId());

    ValidationReviewDetailResponse review = reviewQueryService.reviewByExtractionResult(result.getId());

    assertThat(review.validationRun().validationRunId()).isEqualTo(handoff.validationRunId());
    assertThat(review.extraction().extractionResultId()).isEqualTo(result.getId());
  }

  @Test
  void foreignTenantCannotReadAnotherTenantsReview() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    seedCatalog(tenantA, "SKU-A", new BigDecimal("20"));
    ExtractionResult result = aiWorkerResult(tenantA, "SUCCEEDED", "READY_FOR_VALIDATION", 0.9,
        extraction(0.9, field("customer_hint", "Acme"), line(1, "SKU-A", "Filter", "2", "EA", 0.9)));
    AdvisoryValidationHandoffResult handoff = handoffService.handoff(result.getId());

    TenantContext.setTenantId(UUID.randomUUID());
    assertThatThrownBy(() -> reviewQueryService.reviewByValidationRun(handoff.validationRunId()))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("validation_run_not_found");
    assertThatThrownBy(() -> reviewQueryService.reviewByExtractionResult(result.getId()))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("extraction_result_not_found");
  }

  @Test
  void missingValidationRunFailsClosedNotFound() {
    TenantContext.setTenantId(UUID.randomUUID());
    assertThatThrownBy(() -> reviewQueryService.reviewByValidationRun(UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("validation_run_not_found");
    assertThatThrownBy(() -> reviewQueryService.reviewByExtractionResult(UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("extraction_result_not_found");
  }

  @Test
  void responseDoesNotExposeRawAdvisoryPayloadOrSecrets() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedCatalog(tenantId, "SKU-SEC", new BigDecimal("20"));
    ExtractionResult result = aiWorkerResult(tenantId, "SUCCEEDED", "READY_FOR_VALIDATION", 0.9,
        extraction(0.9, field("customer_hint", "Acme"), line(1, "SKU-SEC", "Filter", "2", "EA", 0.9)));
    AdvisoryValidationHandoffResult handoff = handoffService.handoff(result.getId());

    ValidationReviewDetailResponse review = reviewQueryService.reviewByValidationRun(handoff.validationRunId());

    // The raw advisory wrapper carried a prompt/secret sentinel and wrapper-only markers; none of them
    // may surface in the bounded review payload.
    String rendered = review.toString();
    assertThat(rendered).doesNotContain(SECRET_SENTINEL);
    assertThat(rendered).doesNotContain("untrustedUntilValidation");
    assertThat(rendered).doesNotContain("schemaVersion");
    assertThat(rendered).doesNotContain("resultJson");
  }

  @Test
  void issueCountsMatchDeterministicArtifacts() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    // Quantity 0 produces a blocking INVALID_QUANTITY issue in the deterministic engine.
    ExtractionResult result = aiWorkerResult(tenantId, "SUCCEEDED", "READY_FOR_VALIDATION", 0.9,
        extraction(0.9, field("customer_hint", "Acme"), line(1, "SKU-Q", "Filter", "0", "EA", 0.9)));
    AdvisoryValidationHandoffResult handoff = handoffService.handoff(result.getId());

    ValidationReviewDetailResponse review = reviewQueryService.reviewByValidationRun(handoff.validationRunId());

    long blockingInList = review.issues().stream().filter(ValidationIssueReviewItem::blocking).count();
    assertThat(review.validationRun().blockingIssueCount()).isEqualTo((int) blockingInList);
    assertThat(review.validationRun().blockingIssueCount()).isEqualTo(handoff.blockingIssueCount());
    assertThat(review.validationRun().blockingIssueCount()).isGreaterThanOrEqualTo(1);
    assertThat(review.validationRun().routingDecision()).isEqualTo("BLOCKED_UNTIL_FIXED");
    // FIX_LINE_ITEM hint is enabled when blocking line work exists.
    assertThat(actionByName(review, ValidationReviewDtos.ACTION_FIX_LINE_ITEM).enabled()).isTrue();
  }

  @Test
  void sourceEvidenceSnippetsAreBounded() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedCatalog(tenantId, "SKU-EVID", new BigDecimal("20"));
    ExtractionResult result = aiWorkerResult(tenantId, "SUCCEEDED", "READY_FOR_VALIDATION", 0.9,
        extraction(0.9, field("customer_hint", "Acme"), line(1, "SKU-EVID", "Filter", "2", "EA", 0.9)));
    AdvisoryValidationHandoffResult handoff = handoffService.handoff(result.getId());

    ValidationReviewDetailResponse review = reviewQueryService.reviewByValidationRun(handoff.validationRunId());

    assertThat(review.sourceEvidence()).isNotEmpty();
    for (SourceEvidenceReviewItem evidence : review.sourceEvidence()) {
      assertThat(evidence.snippet()).isNotNull();
      assertThat(evidence.snippet().length()).isLessThanOrEqualTo(ValidationReviewDtos.MAX_SNIPPET_LENGTH);
    }
  }

  // --- helpers -----------------------------------------------------------------------------------

  private void seedCatalog(UUID tenantId, String sku, BigDecimal stock) {
    Product product = products.save(new Product(tenantId, sku, "Filter", "Filter", "Filters", "Brand", "Maker", "EA", "ACTIVE", new BigDecimal("10"), "USD", NOW));
    Location location = locations.save(new Location(tenantId, "MAIN", "Main", "WAREHOUSE", null, "Almaty", "KZ", true, NOW));
    customers.save(new CustomerAccount(tenantId, null, "ACME", "Acme Filter", "Acme Filter", null, "ACTIVE", "USD", location.getId(), NOW));
    inventory.save(new InventorySnapshot(tenantId, product.getId(), location.getId(), stock, stock, BigDecimal.ZERO, NOW, "TEST", null, NOW));
    prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
  }

  private static AllowedReviewAction actionByName(ValidationReviewDetailResponse review, String action) {
    return review.allowedActions().stream().filter(a -> a.action().equals(action)).findFirst().orElseThrow();
  }

  private ExtractionResult aiWorkerResult(UUID tenantId, String workerStatus, String validationStatus, double overall, Map<String, Object> extraction) {
    Map<String, Object> wrapper = new LinkedHashMap<>();
    wrapper.put("advisoryOnly", true);
    wrapper.put("source", "AI_WORKER");
    wrapper.put("untrustedUntilValidation", true);
    wrapper.put("workerStatus", workerStatus);
    wrapper.put("schemaVersion", "op-cap-07c.v1");
    wrapper.put("promptText", SECRET_SENTINEL);
    wrapper.put("safeFailureReason", null);
    wrapper.put("extraction", extraction);
    // Persisted directly via repository to mirror AI-worker intake; the handoff service reads it back.
    return extractionResultSave(tenantId, overall, json.writeObject(wrapper), validationStatus);
  }

  @Autowired private com.orderpilot.domain.extraction.ExtractionResultRepository extractionResults;

  private ExtractionResult extractionResultSave(UUID tenantId, double overall, String resultJson, String validationStatus) {
    return extractionResults.save(new ExtractionResult(
        tenantId, UUID.randomUUID(), "CHANNEL_MESSAGE", UUID.randomUUID(), "RFQ", "message",
        BigDecimal.valueOf(overall), resultJson, validationStatus, NOW));
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
