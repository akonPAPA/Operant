package com.orderpilot.application.services.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.AiValidationDtos.AiValidationResultView;
import com.orderpilot.api.dto.AiValidationHandoffDtos.AiValidationHandoffView;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.application.services.product.ProductIntelligenceService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.extraction.ExtractionResult;
import com.orderpilot.domain.extraction.ExtractionResultRepository;
import com.orderpilot.domain.extraction.ExtractionRun;
import com.orderpilot.domain.extraction.ExtractionRunRepository;
import com.orderpilot.domain.intake.ProcessingJob;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import com.orderpilot.domain.inventory.InventorySnapshot;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.pricing.PriceRule;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.validation.AiValidationHandoffRepository;
import com.orderpilot.domain.validation.AiValidationHandoffReviewRepository;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-07F AI validation handoff tests: routing→status/draft-eligibility mapping, idempotency,
 * tenant fail-closed, bounded audit, and no business/master-data mutation. Validations are produced
 * by the real OP-CAP-07E service so fixtures are realistic end-to-end.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    AiValidationHandoffService.class,
    ExtractionAdvisoryValidationService.class,
    ValidationEngineService.class,
    ProductIntelligenceService.class,
    AuditEventService.class,
    JsonSupport.class,
    CoreConfiguration.class,
    AiValidationHandoffReviewService.class,
    AiValidationHandoffServiceTest.JacksonTestConfig.class
})
class AiValidationHandoffServiceTest {
  @Autowired private AiValidationHandoffService handoffService;
  @Autowired private AiValidationHandoffReviewService reviewService;
  @Autowired private ExtractionAdvisoryValidationService validationService;
  @Autowired private AiValidationHandoffRepository handoffRepository;
  @Autowired private AiValidationHandoffReviewRepository handoffReviewRepository;
  @Autowired private ProcessingJobRepository jobRepository;
  @Autowired private ExtractionRunRepository runRepository;
  @Autowired private ExtractionResultRepository resultRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private ProductRepository productRepository;
  @Autowired private CustomerAccountRepository customerAccountRepository;
  @Autowired private InventorySnapshotRepository inventorySnapshotRepository;
  @Autowired private PriceRuleRepository priceRuleRepository;
  @Autowired private DraftQuoteRepository draftQuoteRepository;
  @Autowired private DraftOrderRepository draftOrderRepository;
  @Autowired private ObjectMapper objectMapper;

  private static final Instant T0 = Instant.parse("2026-06-06T00:00:00Z");

  @TestConfiguration
  static class JacksonTestConfig {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  private long countAudits(String action) {
    return auditEventRepository.findAll().stream().filter(e -> action.equals(e.getAction())).count();
  }

  // --- fixtures (mirror the 07E end-to-end seeding) ---

  private void seedKnownCustomer(UUID tenantId, String accountCode) {
    customerAccountRepository.save(new CustomerAccount(tenantId, null, accountCode, "Acme Parts LLC",
        "Acme Parts", null, "ACTIVE", "USD", null, T0));
  }

  private void seedKnownProductWithStockAndPrice(UUID tenantId, String sku) {
    Product product = productRepository.save(new Product(tenantId, sku, "Brake Pads", "Front brake pads",
        "BRAKES", "BrandX", "MakerY", "PCS", "ACTIVE", new BigDecimal("10.00"), "USD", T0));
    inventorySnapshotRepository.save(new InventorySnapshot(tenantId, product.getId(), UUID.randomUUID(),
        new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ZERO, Instant.now(), "TEST", null, T0));
    priceRuleRepository.save(new PriceRule(tenantId, product.getId(), null, null, null,
        new BigDecimal("1"), "PCS", new BigDecimal("100.00"), "USD",
        Instant.now().minusSeconds(3600), null, 100, T0));
  }

  private Map<String, Object> lineItem(String sku, String qty) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("line_number", 1);
    line.put("raw_sku", sku);
    line.put("raw_description", "brake pads");
    line.put("raw_quantity", qty);
    line.put("raw_uom", "PCS");
    line.put("confidence", 0.8);
    return line;
  }

  private Map<String, Object> extraction(String customerHint, double confidence, List<Map<String, Object>> lines) {
    Map<String, Object> ex = new LinkedHashMap<>();
    ex.put("detected_intent", "RFQ");
    ex.put("document_type", "message");
    ex.put("overall_confidence", confidence);
    ex.put("customer_hints", customerHint == null ? List.of() : List.of(customerHint));
    ex.put("line_items", lines);
    return ex;
  }

  private UUID seedAdvisory(UUID tenantId, String workerStatus, Map<String, Object> extraction, List<String> signals) {
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = jobRepository.save(new ProcessingJob(tenantId, "MESSAGE_PROCESSING", "CHANNEL_MESSAGE", sourceId, 100, T0));
    ExtractionRun run = runRepository.save(new ExtractionRun(tenantId, "CHANNEL_MESSAGE", sourceId, job.getId(),
        "AI_WORKER", "rule-based-understanding", "RULE_BASED", null, "op-cap-07c.v1", T0));
    Map<String, Object> wrapper = new LinkedHashMap<>();
    wrapper.put("advisoryOnly", true);
    wrapper.put("source", "AI_WORKER");
    wrapper.put("untrustedUntilValidation", true);
    wrapper.put("workerStatus", workerStatus);
    wrapper.put("schemaVersion", "op-cap-07c.v1");
    wrapper.put("promptInjectionSignals", signals);
    wrapper.put("extraction", extraction);
    Object conf = extraction.getOrDefault("overall_confidence", 0.0);
    ExtractionResult er = resultRepository.save(new ExtractionResult(tenantId, run.getId(), "CHANNEL_MESSAGE", sourceId,
        "RFQ", "message", new BigDecimal(conf.toString()), writeJson(wrapper), "READY_FOR_VALIDATION", T0));
    return er.getId();
  }

  /** Produce a real AiExtractionValidation via the 07E service and return its validationId. */
  private UUID validate(UUID tenantId, String workerStatus, String customerHint, double confidence,
      List<Map<String, Object>> lines, List<String> signals) {
    UUID erId = seedAdvisory(tenantId, workerStatus, extraction(customerHint, confidence, lines), signals);
    AiValidationResultView v = validationService.validate(erId);
    return v.validationId();
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  // --- tests ---

  @Test
  void readyForDraftReviewCreatesOneReadyHandoff() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownCustomer(tenantId, "ACME");
    seedKnownProductWithStockAndPrice(tenantId, "BP-100");
    UUID validationId = validate(tenantId, "SUCCEEDED", "ACME", 0.82, List.of(lineItem("BP-100", "20")), List.of());

    AiValidationHandoffView handoff = handoffService.generate(validationId);

    assertThat(handoff.status()).isEqualTo("READY_FOR_DRAFT_REVIEW");
    assertThat(handoff.routingDecision()).isEqualTo("READY_FOR_DRAFT_REVIEW");
    assertThat(handoff.draftEligible()).isTrue();
    assertThat(handoff.intent()).isEqualTo("RFQ");
    assertThat(handoff.lineCount()).isEqualTo(1);
    assertThat(handoff.customerRef()).isEqualTo("ACME");
    assertThat(handoffRepository.findByTenantIdAndValidationId(tenantId, validationId)).isPresent();
  }

  @Test
  void needsHumanReviewCreatesHumanReviewHandoffWithIssueSummary() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownProductWithStockAndPrice(tenantId, "BP-100");
    UUID validationId = validate(tenantId, "SUCCEEDED", "NOPE", 0.82, List.of(lineItem("BP-100", "20")), List.of());

    AiValidationHandoffView handoff = handoffService.generate(validationId);

    assertThat(handoff.status()).isEqualTo("NEEDS_HUMAN_REVIEW");
    assertThat(handoff.draftEligible()).isFalse();
    assertThat(handoff.unknownCustomer()).isTrue();
    assertThat(handoff.issueCount()).isGreaterThan(0);
    assertThat(handoff.issueSummary()).contains("UNKNOWN_CUSTOMER");
  }

  @Test
  void blockedInvalidExtractionIsVisibleButNotDraftEligible() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownCustomer(tenantId, "ACME");
    seedKnownProductWithStockAndPrice(tenantId, "BP-100");
    UUID validationId = validate(tenantId, "SUCCEEDED", "ACME", 0.82, List.of(lineItem("BP-100", "-5")), List.of());

    AiValidationHandoffView handoff = handoffService.generate(validationId);

    assertThat(handoff.status()).isEqualTo("BLOCKED_INVALID_EXTRACTION");
    assertThat(handoff.draftEligible()).isFalse();
    assertThat(handoffService.get(handoff.handoffId()).handoffId()).isEqualTo(handoff.handoffId());
  }

  @Test
  void failedValidationIsNotDraftEligible() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID validationId = validate(tenantId, "FAILED", null, 0.0, List.of(), List.of());

    AiValidationHandoffView handoff = handoffService.generate(validationId);

    assertThat(handoff.status()).isEqualTo("FAILED_VALIDATION");
    assertThat(handoff.draftEligible()).isFalse();
  }

  @Test
  void regenerationIsIdempotent() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownProductWithStockAndPrice(tenantId, "BP-100");
    UUID validationId = validate(tenantId, "SUCCEEDED", "NOPE", 0.82, List.of(lineItem("BP-100", "20")), List.of());

    AiValidationHandoffView first = handoffService.generate(validationId);
    AiValidationHandoffView second = handoffService.generate(validationId);

    assertThat(second.handoffId()).isEqualTo(first.handoffId());
    assertThat(handoffRepository.count()).isEqualTo(1);
  }

  @Test
  void tenantMismatchFailsClosed() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    seedKnownCustomer(tenantA, "ACME");
    seedKnownProductWithStockAndPrice(tenantA, "BP-100");
    UUID validationId = validate(tenantA, "SUCCEEDED", "ACME", 0.82, List.of(lineItem("BP-100", "20")), List.of());

    TenantContext.setTenantId(UUID.randomUUID());
    assertThatThrownBy(() -> handoffService.generate(validationId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not found for tenant");
  }

  @Test
  void missingValidationIdFailsSafely() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);

    assertThatThrownBy(() -> handoffService.generate(UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not found for tenant");
  }

  @Test
  void emitsBoundedAuditEventWithoutRawContent() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownCustomer(tenantId, "ACME");
    seedKnownProductWithStockAndPrice(tenantId, "BP-100");
    UUID validationId = validate(tenantId, "SUCCEEDED", "ACME", 0.82, List.of(lineItem("BP-100", "20")), List.of());
    long before = countAudits("ai_validation_handoff.created");

    AiValidationHandoffView handoff = handoffService.generate(validationId);

    assertThat(countAudits("ai_validation_handoff.created") - before).isEqualTo(1);
    var event = auditEventRepository.findAll().stream()
        .filter(e -> "ai_validation_handoff.created".equals(e.getAction())
            && handoff.handoffId().toString().equals(e.getEntityId()))
        .findFirst().orElseThrow();
    String metadata = event.getMetadata();
    assertThat(metadata).contains("routingDecision").contains("riskLevel").contains("status").contains("draftEligible");
    // Bounded: never carries raw message/document text, the extraction payload, or result JSON.
    assertThat(metadata).doesNotContain("brake pads").doesNotContain("result_json").doesNotContain("customer_hints");
  }

  @Test
  void regenerationEmitsUpdateAudit() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownProductWithStockAndPrice(tenantId, "BP-100");
    UUID validationId = validate(tenantId, "SUCCEEDED", "ACME", 0.82, List.of(lineItem("BP-100", "20")), List.of());
    handoffService.generate(validationId);
    long updatesBefore = countAudits("ai_validation_handoff.updated");

    handoffService.generate(validationId);

    assertThat(countAudits("ai_validation_handoff.updated") - updatesBefore).isEqualTo(1);
  }

  @Test
  void doesNotMutateMasterDataOrQuoteOrderEntities() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownCustomer(tenantId, "ACME");
    seedKnownProductWithStockAndPrice(tenantId, "BP-100");
    UUID validationId = validate(tenantId, "SUCCEEDED", "ACME", 0.82, List.of(lineItem("BP-100", "20")), List.of());
    long products = productRepository.count();
    long customers = customerAccountRepository.count();
    long inventory = inventorySnapshotRepository.count();
    long prices = priceRuleRepository.count();
    long quotes = draftQuoteRepository.count();
    long orders = draftOrderRepository.count();

    handoffService.generate(validationId);

    assertThat(productRepository.count()).isEqualTo(products);
    assertThat(customerAccountRepository.count()).isEqualTo(customers);
    assertThat(inventorySnapshotRepository.count()).isEqualTo(inventory);
    assertThat(priceRuleRepository.count()).isEqualTo(prices);
    assertThat(draftQuoteRepository.count()).isEqualTo(quotes);
    assertThat(draftOrderRepository.count()).isEqualTo(orders);
  }

  @Test
  void operatorCanApproveDraftEligibleHandoffForFutureDraftPreparationOnly() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownCustomer(tenantId, "ACME");
    seedKnownProductWithStockAndPrice(tenantId, "BP-100");
    UUID validationId = validate(tenantId, "SUCCEEDED", "ACME", 0.82, List.of(lineItem("BP-100", "20")), List.of());
    AiValidationHandoffView handoff = handoffService.generate(validationId);
    long quotes = draftQuoteRepository.count();
    long orders = draftOrderRepository.count();

    var started = reviewService.startReview(handoff.handoffId(), new com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffStartReviewRequest("operator-1"));
    var decided = reviewService.decide(handoff.handoffId(),
        new com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffDecisionRequest(
            "APPROVE_FOR_DRAFT_PREPARATION", "VALIDATED_BY_OPERATOR", "bounded note", "operator-1"));

    assertThat(started.reviewStatus()).isEqualTo("IN_REVIEW");
    assertThat(decided.reviewStatus()).isEqualTo("DRAFT_PREPARATION_READY");
    assertThat(decided.decision()).isEqualTo("APPROVE_FOR_DRAFT_PREPARATION");
    assertThat(decided.externalExecution()).isEqualTo("DISABLED");
    assertThat(handoffReviewRepository.findByTenantIdAndHandoffId(tenantId, handoff.handoffId())).isPresent();
    assertThat(draftQuoteRepository.count()).isEqualTo(quotes);
    assertThat(draftOrderRepository.count()).isEqualTo(orders);
  }

  @Test
  void operatorCannotApproveNonDraftEligibleHandoffForDraftPreparation() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownProductWithStockAndPrice(tenantId, "BP-100");
    UUID validationId = validate(tenantId, "SUCCEEDED", "NOPE", 0.82, List.of(lineItem("BP-100", "20")), List.of());
    AiValidationHandoffView handoff = handoffService.generate(validationId);

    assertThatThrownBy(() -> reviewService.decide(handoff.handoffId(),
        new com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffDecisionRequest(
            "APPROVE_FOR_DRAFT_PREPARATION", "NOT_READY", "bounded note", "operator-1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not draft eligible");
  }

  @Test
  void operatorCorrectionIsBoundedTenantScopedReviewMetadataOnly() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownProductWithStockAndPrice(tenantId, "BP-100");
    UUID validationId = validate(tenantId, "SUCCEEDED", "NOPE", 0.82, List.of(lineItem("BP-100", "20")), List.of());
    AiValidationHandoffView handoff = handoffService.generate(validationId);

    var review = reviewService.recordCorrection(handoff.handoffId(),
        new com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffCorrectionRequest(
            "Customer account should be ACME after operator review",
            "RFQ",
            "ACME",
            1,
            "operator-1"));

    assertThat(review.reviewStatus()).isEqualTo("CORRECTION_REQUESTED");
    assertThat(review.correctionSummary()).contains("ACME");
    assertThat(review.correctedIntent()).isEqualTo("RFQ");
    assertThat(review.correctedLineCount()).isEqualTo(1);
    assertThat(countAudits("ai_validation_handoff_review.correction_recorded")).isEqualTo(1);
  }

  @Test
  void handoffReviewTenantMismatchFailsClosed() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    seedKnownCustomer(tenantA, "ACME");
    seedKnownProductWithStockAndPrice(tenantA, "BP-100");
    UUID validationId = validate(tenantA, "SUCCEEDED", "ACME", 0.82, List.of(lineItem("BP-100", "20")), List.of());
    AiValidationHandoffView handoff = handoffService.generate(validationId);

    TenantContext.setTenantId(UUID.randomUUID());

    assertThatThrownBy(() -> reviewService.get(handoff.handoffId()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not found for tenant");
  }

  @Test
  void terminalReviewDecisionCannotBeMutatedAgain() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownCustomer(tenantId, "ACME");
    seedKnownProductWithStockAndPrice(tenantId, "BP-100");
    UUID validationId = validate(tenantId, "SUCCEEDED", "ACME", 0.82, List.of(lineItem("BP-100", "20")), List.of());
    AiValidationHandoffView handoff = handoffService.generate(validationId);
    reviewService.decide(handoff.handoffId(),
        new com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffDecisionRequest(
            "APPROVE_FOR_DRAFT_PREPARATION", "VALIDATED_BY_OPERATOR", null, "operator-1"));

    assertThatThrownBy(() -> reviewService.recordCorrection(handoff.handoffId(),
        new com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffCorrectionRequest(
            "later change", null, null, null, "operator-1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("already terminal");
  }
}
