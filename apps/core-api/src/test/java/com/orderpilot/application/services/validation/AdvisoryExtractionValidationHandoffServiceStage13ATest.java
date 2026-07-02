package com.orderpilot.application.services.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.AdvisoryValidationHandoffDtos.AdvisoryValidationHandoffResult;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
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
import com.orderpilot.domain.validation.ValidationRunRepository;
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
 * OP-CAP-13A — advisory extraction → deterministic validation handoff tests.
 *
 * <p>Proves a persisted AI-worker advisory {@link ExtractionResult} can be safely handed into the
 * existing deterministic validation engine: a valid result decomposes into untrusted normalized rows
 * and produces a {@code ValidationRun} with NO business mutation; failed/unsafe results fail closed
 * with no decomposition and no run; low confidence and invalid quantity route to review; tenant
 * mismatch is rejected; retries are idempotent; and source/evidence context is preserved.
 */
@SpringBootTest
@ActiveProfiles("test")
class AdvisoryExtractionValidationHandoffServiceStage13ATest {
  private static final Instant NOW = Instant.parse("2026-06-09T00:00:00Z");

  @Autowired private AdvisoryExtractionValidationHandoffService service;
  @Autowired private ExtractionResultRepository extractionResults;
  @Autowired private ExtractedLineItemRepository lines;
  @Autowired private ValidationRunRepository validationRuns;
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
  void acceptedHandoffDecomposesAndValidatesWithoutBusinessMutation() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product product = product(tenantId, "SKU-OK", "Filter", "10");
    Location location = locations.save(new Location(tenantId, "MAIN", "Main", "WAREHOUSE", null, "Almaty", "KZ", true, NOW));
    customer(tenantId, "ACME", "Acme Filter", location.getId());
    inventory.save(new InventorySnapshot(tenantId, product.getId(), location.getId(), new BigDecimal("20"), new BigDecimal("20"), BigDecimal.ZERO, NOW, "TEST", null, NOW));
    prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));

    ExtractionResult result = aiWorkerResult(tenantId, "SUCCEEDED", "READY_FOR_VALIDATION", 0.9,
        extraction(0.9, field("customer_hint", "Acme Filter"), line(1, "SKU-OK", "Filter", "2", "EA", 0.9)), null);
    long quotesBefore = draftQuotes.count();
    long ordersBefore = draftOrders.count();

    AdvisoryValidationHandoffResult handoff = service.handoff(result.getId());

    assertThat(handoff.handoffStatus()).isEqualTo("ACCEPTED");
    assertThat(handoff.advisoryOnly()).isTrue();
    assertThat(handoff.duplicate()).isFalse();
    assertThat(handoff.validationRunId()).isNotNull();
    assertThat(handoff.validationStatus()).isNotBlank();
    assertThat(handoff.decomposedLineCount()).isEqualTo(1);
    // Source context preserved end-to-end.
    assertThat(handoff.sourceType()).isEqualTo(result.getSourceType());
    assertThat(handoff.sourceId()).isEqualTo(result.getSourceId());
    // Advisory rows were created and a deterministic validation run exists.
    List<ExtractedLineItem> decomposed = lines.findByTenantIdAndExtractionResultId(tenantId, result.getId());
    assertThat(decomposed).hasSize(1);
    assertThat(decomposed.get(0).getSourceEvidenceId()).isNotNull(); // evidence preserved
    assertThat(validationRuns.findByTenantIdAndExtractionResultIdOrderByCreatedAtDesc(tenantId, result.getId())).isNotEmpty();
    // No business records were created by the handoff.
    assertThat(draftQuotes.count()).isEqualTo(quotesBefore);
    assertThat(draftOrders.count()).isEqualTo(ordersBefore);
  }

  @Test
  void failedExtractionFailsClosedWithoutDecompositionOrRun() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ExtractionResult result = aiWorkerResult(tenantId, "FAILED", "FAILED", 0.0, Map.of(), "provider_error");

    AdvisoryValidationHandoffResult handoff = service.handoff(result.getId());

    assertThat(handoff.handoffStatus()).isEqualTo("FAILED_EXTRACTION");
    assertThat(handoff.validationRunId()).isNull();
    assertThat(handoff.decomposedLineCount()).isZero();
    assertThat(handoff.failureCode()).isEqualTo("provider_error");
    assertThat(handoff.advisoryOnly()).isTrue();
    assertThat(lines.findByTenantIdAndExtractionResultId(tenantId, result.getId())).isEmpty();
    assertThat(validationRuns.findByTenantIdAndExtractionResultIdOrderByCreatedAtDesc(tenantId, result.getId())).isEmpty();
  }

  @Test
  void nestedBusinessActionKeyIsRejectedWithoutDecomposition() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Map<String, Object> unsafeLine = new LinkedHashMap<>(line(1, "SKU-X", "Thing", "2", "EA", 0.9));
    unsafeLine.put("create_order", Map.of("sku", "SKU-X"));
    ExtractionResult result = aiWorkerResult(tenantId, "SUCCEEDED", "READY_FOR_VALIDATION", 0.9,
        extraction(0.9, null, unsafeLine), null);

    AdvisoryValidationHandoffResult handoff = service.handoff(result.getId());

    assertThat(handoff.handoffStatus()).isEqualTo("UNSAFE_OUTPUT_REJECTED");
    assertThat(handoff.failureCode()).isEqualTo("forbidden_action_key");
    assertThat(handoff.validationRunId()).isNull();
    assertThat(lines.findByTenantIdAndExtractionResultId(tenantId, result.getId())).isEmpty();
    assertThat(validationRuns.findByTenantIdAndExtractionResultIdOrderByCreatedAtDesc(tenantId, result.getId())).isEmpty();
  }

  @Test
  void lowConfidenceRoutesToReview() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ExtractionResult result = aiWorkerResult(tenantId, "NEEDS_REVIEW", "NEEDS_REVIEW", 0.30,
        extraction(0.30, field("customer_hint", "Acme"), line(1, "SKU-LOW", "Thing", "2", "EA", 0.30)), null);

    AdvisoryValidationHandoffResult handoff = service.handoff(result.getId());

    assertThat(handoff.handoffStatus()).isEqualTo("ACCEPTED");
    assertThat(handoff.validationIssueCount()).isGreaterThanOrEqualTo(1);
    assertThat(handoff.approvalRequirementCount()).isGreaterThanOrEqualTo(1);
    assertThat(handoff.routingRecommendation()).isIn("NEEDS_OPERATOR_REVIEW", "BLOCKED_UNTIL_FIXED");
  }

  @Test
  void invalidQuantityCreatesBlockingIssue() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ExtractionResult result = aiWorkerResult(tenantId, "SUCCEEDED", "READY_FOR_VALIDATION", 0.9,
        extraction(0.9, field("customer_hint", "Acme"), line(1, "SKU-Q", "Thing", "0", "EA", 0.9)), null);

    AdvisoryValidationHandoffResult handoff = service.handoff(result.getId());

    assertThat(handoff.handoffStatus()).isEqualTo("ACCEPTED");
    assertThat(handoff.blockingIssueCount()).isGreaterThanOrEqualTo(1);
    assertThat(handoff.routingRecommendation()).isEqualTo("BLOCKED_UNTIL_FIXED");
  }

  @Test
  void tenantMismatchIsRejectedFailClosed() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    ExtractionResult result = aiWorkerResult(tenantA, "SUCCEEDED", "READY_FOR_VALIDATION", 0.9,
        extraction(0.9, field("customer_hint", "Acme"), line(1, "SKU-T", "Thing", "2", "EA", 0.9)), null);

    TenantContext.setTenantId(UUID.randomUUID());
    assertThatThrownBy(() -> service.handoff(result.getId()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("extraction_result_not_found");
    // Nothing decomposed or validated for tenant A by the foreign attempt.
    assertThat(lines.findByTenantIdAndExtractionResultId(tenantA, result.getId())).isEmpty();
    assertThat(validationRuns.findByTenantIdAndExtractionResultIdOrderByCreatedAtDesc(tenantA, result.getId())).isEmpty();
  }

  @Test
  void duplicateHandoffIsIdempotent() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ExtractionResult result = aiWorkerResult(tenantId, "SUCCEEDED", "READY_FOR_VALIDATION", 0.9,
        extraction(0.9, field("customer_hint", "Acme"), line(1, "SKU-DUP", "Thing", "2", "EA", 0.9)), null);

    AdvisoryValidationHandoffResult first = service.handoff(result.getId());
    AdvisoryValidationHandoffResult second = service.handoff(result.getId());

    assertThat(first.duplicate()).isFalse();
    assertThat(second.duplicate()).isTrue();
    assertThat(second.validationRunId()).isEqualTo(first.validationRunId());
    // No duplicate decomposition or validation run on retry.
    assertThat(lines.findByTenantIdAndExtractionResultId(tenantId, result.getId())).hasSize(1);
    assertThat(validationRuns.findByTenantIdAndExtractionResultIdOrderByCreatedAtDesc(tenantId, result.getId())).hasSize(1);
  }

  @Test
  void nonAiWorkerResultIsRejected() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    // A core-internal extraction result (no AI-worker advisory wrapper "source").
    ExtractionResult result = extractionResults.save(new ExtractionResult(
        tenantId, UUID.randomUUID(), "CHANNEL_MESSAGE", UUID.randomUUID(), "RFQ", "message",
        new BigDecimal("0.9"), "{\"advisoryOnly\":true}", "READY_FOR_VALIDATION", NOW));

    assertThatThrownBy(() -> service.handoff(result.getId()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not_ai_worker_advisory_result");
  }

  // --- helpers -----------------------------------------------------------------------------------

  private ExtractionResult aiWorkerResult(
      UUID tenantId, String workerStatus, String validationStatus, double overall,
      Map<String, Object> extraction, String safeFailureReason) {
    Map<String, Object> wrapper = new LinkedHashMap<>();
    wrapper.put("advisoryOnly", true);
    wrapper.put("source", "AI_WORKER");
    wrapper.put("untrustedUntilValidation", true);
    wrapper.put("workerStatus", workerStatus);
    wrapper.put("schemaVersion", "op-cap-07c.v1");
    wrapper.put("safeFailureReason", safeFailureReason);
    wrapper.put("extraction", extraction);
    return extractionResults.save(new ExtractionResult(
        tenantId, UUID.randomUUID(), "CHANNEL_MESSAGE", UUID.randomUUID(), "RFQ", "message",
        BigDecimal.valueOf(overall), json.writeObject(wrapper), validationStatus, NOW));
  }

  private static Map<String, Object> extraction(double overall, Map<String, Object> field, Map<String, Object> line) {
    Map<String, Object> extraction = new LinkedHashMap<>();
    extraction.put("detected_intent", "RFQ");
    extraction.put("overall_confidence", overall);
    if (field != null) {
      extraction.put("fields", List.of(field));
    }
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

  private CustomerAccount customer(UUID tenantId, String accountCode, String name, UUID defaultLocationId) {
    return customers.save(new CustomerAccount(tenantId, null, accountCode, name, name, null, "ACTIVE", "USD", defaultLocationId, NOW));
  }

  private Product product(UUID tenantId, String sku, String name, String cost) {
    return products.save(new Product(tenantId, sku, name, name, "Filters", "Brand", "Maker", "EA", "ACTIVE", new BigDecimal(cost), "USD", NOW));
  }
}
