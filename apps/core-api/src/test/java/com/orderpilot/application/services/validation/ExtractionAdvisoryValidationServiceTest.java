package com.orderpilot.application.services.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.AiValidationDtos.AiValidationResultView;
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
import com.orderpilot.domain.validation.AiExtractionValidationIssueRepository;
import com.orderpilot.domain.validation.AiExtractionValidationRepository;
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
 * OP-CAP-07E deterministic validation &amp; risk routing tests for advisory AI extraction results.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    ExtractionAdvisoryValidationService.class,
    ValidationEngineService.class,
    ProductIntelligenceService.class,
    AuditEventService.class,
    JsonSupport.class,
    CoreConfiguration.class,
    ExtractionAdvisoryValidationServiceTest.JacksonTestConfig.class
})
class ExtractionAdvisoryValidationServiceTest {
  @Autowired private ExtractionAdvisoryValidationService service;
  @Autowired private ProcessingJobRepository jobRepository;
  @Autowired private ExtractionRunRepository runRepository;
  @Autowired private ExtractionResultRepository resultRepository;
  @Autowired private AiExtractionValidationRepository validationRepository;
  @Autowired private AiExtractionValidationIssueRepository issueRepository;
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

  // --- fixtures ---

  private void seedKnownCustomer(UUID tenantId, String accountCode) {
    customerAccountRepository.save(new CustomerAccount(tenantId, null, accountCode, "Acme Parts LLC",
        "Acme Parts", null, "ACTIVE", "USD", null, T0));
  }

  private UUID seedKnownProductWithStockAndPrice(UUID tenantId, String sku) {
    Product product = productRepository.save(new Product(tenantId, sku, "Brake Pads", "Front brake pads",
        "BRAKES", "BrandX", "MakerY", "PCS", "ACTIVE", new BigDecimal("10.00"), "USD", T0));
    inventorySnapshotRepository.save(new InventorySnapshot(tenantId, product.getId(), UUID.randomUUID(),
        new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ZERO, Instant.now(), "TEST", null, T0));
    priceRuleRepository.save(new PriceRule(tenantId, product.getId(), null, null, null,
        new BigDecimal("1"), "PCS", new BigDecimal("100.00"), "USD",
        Instant.now().minusSeconds(3600), null, 100, T0));
    return product.getId();
  }

  private Map<String, Object> lineItem(String sku, String qty, String uom, double confidence) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("line_number", 1);
    line.put("raw_sku", sku);
    line.put("raw_description", "brake pads");
    line.put("raw_quantity", qty);
    line.put("raw_uom", uom);
    line.put("confidence", confidence);
    return line;
  }

  private Map<String, Object> extraction(String intent, String customerHint, double confidence, List<Map<String, Object>> lines) {
    Map<String, Object> ex = new LinkedHashMap<>();
    ex.put("detected_intent", intent);
    ex.put("document_type", "message");
    ex.put("overall_confidence", confidence);
    ex.put("customer_hints", customerHint == null ? List.of() : List.of(customerHint));
    ex.put("line_items", lines);
    return ex;
  }

  private UUID seedAdvisory(UUID tenantId, String sourceType, String workerStatus,
      Map<String, Object> extraction, List<String> signals) {
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = jobRepository.save(new ProcessingJob(tenantId, "MESSAGE_PROCESSING", sourceType, sourceId, 100, T0));
    ExtractionRun run = runRepository.save(new ExtractionRun(tenantId, sourceType, sourceId, job.getId(),
        "AI_WORKER", "rule-based-understanding", "RULE_BASED", null, "op-cap-07c.v1", T0));
    Map<String, Object> wrapper = new LinkedHashMap<>();
    wrapper.put("advisoryOnly", true);
    wrapper.put("source", "AI_WORKER");
    wrapper.put("untrustedUntilValidation", true);
    wrapper.put("workerStatus", workerStatus);
    wrapper.put("schemaVersion", "op-cap-07c.v1");
    wrapper.put("promptInjectionSignals", signals);
    wrapper.put("extraction", extraction);
    String resultJson = writeJson(wrapper);
    Object intent = extraction.getOrDefault("detected_intent", "unknown");
    Object conf = extraction.getOrDefault("overall_confidence", 0.0);
    BigDecimal overall = new BigDecimal(conf.toString());
    ExtractionResult er = resultRepository.save(new ExtractionResult(tenantId, run.getId(), sourceType, sourceId,
        intent.toString(), "message", overall, resultJson, "READY_FOR_VALIDATION", T0));
    return er.getId();
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
  void knownCustomerProductPositiveQuantityIsLowRiskReadyForDraftReview() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownCustomer(tenantId, "ACME");
    seedKnownProductWithStockAndPrice(tenantId, "BP-100");
    UUID erId = seedAdvisory(tenantId, "CHANNEL_MESSAGE", "SUCCEEDED",
        extraction("RFQ", "ACME", 0.82, List.of(lineItem("BP-100", "20", "PCS", 0.8))), List.of());

    AiValidationResultView result = service.validate(erId);

    assertThat(result.riskLevel()).isEqualTo("LOW");
    assertThat(result.routingDecision()).isEqualTo("READY_FOR_DRAFT_REVIEW");
    assertThat(result.issueCount()).isZero();
    assertThat(result.advisoryOnly()).isTrue();
    assertThat(jobRepository.findByIdAndTenantId(result.processingJobId(), tenantId).orElseThrow().getStatus())
        .isEqualTo("SUCCEEDED");
  }

  @Test
  void unknownCustomerRoutesToHumanReview() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownProductWithStockAndPrice(tenantId, "BP-100");
    UUID erId = seedAdvisory(tenantId, "CHANNEL_MESSAGE", "SUCCEEDED",
        extraction("RFQ", "NOPE", 0.82, List.of(lineItem("BP-100", "20", "PCS", 0.8))), List.of());

    AiValidationResultView result = service.validate(erId);

    assertThat(result.unknownCustomer()).isTrue();
    assertThat(result.routingDecision()).isEqualTo("NEEDS_HUMAN_REVIEW");
    assertThat(issueCodes(result)).contains("UNKNOWN_CUSTOMER");
  }

  @Test
  void unknownProductRoutesToHumanReview() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownCustomer(tenantId, "ACME");
    UUID erId = seedAdvisory(tenantId, "CHANNEL_MESSAGE", "SUCCEEDED",
        extraction("RFQ", "ACME", 0.82, List.of(lineItem("ZZZ-999", "20", "PCS", 0.8))), List.of());

    AiValidationResultView result = service.validate(erId);

    assertThat(issueCodes(result)).contains("UNKNOWN_PRODUCT");
    assertThat(result.routingDecision()).isEqualTo("NEEDS_HUMAN_REVIEW");
  }

  @Test
  void invalidQuantityIsBlocked() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownCustomer(tenantId, "ACME");
    seedKnownProductWithStockAndPrice(tenantId, "BP-100");
    UUID erId = seedAdvisory(tenantId, "CHANNEL_MESSAGE", "SUCCEEDED",
        extraction("RFQ", "ACME", 0.82, List.of(lineItem("BP-100", "-5", "PCS", 0.8))), List.of());

    AiValidationResultView result = service.validate(erId);

    assertThat(result.riskLevel()).isEqualTo("BLOCKED");
    assertThat(result.routingDecision()).isEqualTo("BLOCKED_INVALID_EXTRACTION");
    assertThat(issueCodes(result)).contains("INVALID_QUANTITY");
    assertThat(jobRepository.findByIdAndTenantId(result.processingJobId(), tenantId).orElseThrow().getStatus())
        .isEqualTo("REJECTED");
  }

  @Test
  void missingLineItemsIsBlockedInvalidExtraction() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownCustomer(tenantId, "ACME");
    UUID erId = seedAdvisory(tenantId, "CHANNEL_MESSAGE", "SUCCEEDED",
        extraction("RFQ", "ACME", 0.82, List.of()), List.of());

    AiValidationResultView result = service.validate(erId);

    assertThat(result.riskLevel()).isEqualTo("BLOCKED");
    assertThat(result.routingDecision()).isEqualTo("BLOCKED_INVALID_EXTRACTION");
    assertThat(issueCodes(result)).contains("MISSING_LINE_ITEMS");
  }

  @Test
  void promptInjectionSignalIsHighRiskAndHumanReview() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownCustomer(tenantId, "ACME");
    seedKnownProductWithStockAndPrice(tenantId, "BP-100");
    UUID erId = seedAdvisory(tenantId, "CHANNEL_MESSAGE", "NEEDS_REVIEW",
        extraction("RFQ", "ACME", 0.82, List.of(lineItem("BP-100", "20", "PCS", 0.8))),
        List.of("ignore previous instructions"));

    AiValidationResultView result = service.validate(erId);

    assertThat(result.riskLevel()).isEqualTo("HIGH");
    assertThat(result.routingDecision()).isEqualTo("NEEDS_HUMAN_REVIEW");
    assertThat(result.promptInjectionSignalCount()).isEqualTo(1);
    assertThat(issueCodes(result)).contains("PROMPT_INJECTION_SIGNAL");
  }

  @Test
  void lowConfidenceRoutesToHumanReview() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownCustomer(tenantId, "ACME");
    seedKnownProductWithStockAndPrice(tenantId, "BP-100");
    UUID erId = seedAdvisory(tenantId, "CHANNEL_MESSAGE", "NEEDS_REVIEW",
        extraction("RFQ", "ACME", 0.30, List.of(lineItem("BP-100", "20", "PCS", 0.8))), List.of());

    AiValidationResultView result = service.validate(erId);

    assertThat(issueCodes(result)).contains("LOW_CONFIDENCE_FIELD");
    assertThat(result.routingDecision()).isEqualTo("NEEDS_HUMAN_REVIEW");
  }

  @Test
  void failedOrRejectedExtractionCannotValidateAsReady() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID failedId = seedAdvisory(tenantId, "CHANNEL_MESSAGE", "FAILED",
        extraction("unknown", null, 0.0, List.of()), List.of());
    UUID rejectedId = seedAdvisory(tenantId, "CHANNEL_MESSAGE", "REJECTED",
        extraction("unknown", null, 0.0, List.of()), List.of());

    AiValidationResultView failed = service.validate(failedId);
    AiValidationResultView rejected = service.validate(rejectedId);

    assertThat(failed.riskLevel()).isEqualTo("BLOCKED");
    assertThat(failed.routingDecision()).isEqualTo("FAILED_VALIDATION");
    assertThat(issueCodes(failed)).contains("PROVIDER_FAILURE");
    assertThat(rejected.routingDecision()).isEqualTo("BLOCKED_INVALID_EXTRACTION");
    assertThat(issueCodes(rejected)).contains("EXTRACTION_REJECTED");
  }

  @Test
  void tenantMismatchIsRejectedFailClosed() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    UUID erId = seedAdvisory(tenantA, "CHANNEL_MESSAGE", "SUCCEEDED",
        extraction("RFQ", "ACME", 0.82, List.of(lineItem("BP-100", "20", "PCS", 0.8))), List.of());

    TenantContext.setTenantId(UUID.randomUUID());
    assertThatThrownBy(() -> service.validate(erId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not found for tenant");
  }

  @Test
  void duplicateValidationIsIdempotent() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownProductWithStockAndPrice(tenantId, "BP-100");
    UUID erId = seedAdvisory(tenantId, "CHANNEL_MESSAGE", "SUCCEEDED",
        extraction("RFQ", "NOPE", 0.82, List.of(lineItem("BP-100", "20", "PCS", 0.8))), List.of());

    AiValidationResultView first = service.validate(erId);
    AiValidationResultView second = service.validate(erId);

    assertThat(second.validationId()).isEqualTo(first.validationId());
    assertThat(second.issueCount()).isEqualTo(first.issueCount());
    assertThat(issueRepository.findByTenantIdAndAiExtractionValidationIdOrderByCreatedAtAsc(tenantId, first.validationId()))
        .hasSize(first.issueCount());
    assertThat(validationRepository.findByTenantIdAndExtractionResultId(tenantId, erId)).isPresent();
  }

  @Test
  void emitsAuditEventWithBoundedMetadata() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownCustomer(tenantId, "ACME");
    seedKnownProductWithStockAndPrice(tenantId, "BP-100");
    long before = countAudits("ai_extraction_validation.completed");
    UUID erId = seedAdvisory(tenantId, "CHANNEL_MESSAGE", "SUCCEEDED",
        extraction("RFQ", "ACME", 0.82, List.of(lineItem("BP-100", "20", "PCS", 0.8))), List.of());

    AiValidationResultView result = service.validate(erId);

    assertThat(countAudits("ai_extraction_validation.completed") - before).isEqualTo(1);
    var event = auditEventRepository.findAll().stream()
        .filter(e -> "ai_extraction_validation.completed".equals(e.getAction())
            && result.validationId().toString().equals(e.getEntityId()))
        .findFirst().orElseThrow();
    String metadata = event.getMetadata();
    assertThat(metadata).contains("riskLevel").contains("routingDecision").contains("issueCount");
    // Bounded: never carries raw customer message text or the extraction payload.
    assertThat(metadata).doesNotContain("brake pads").doesNotContain("detected_intent");
  }

  @Test
  void doesNotMutateBusinessMasterDataOrQuoteOrderEntities() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownCustomer(tenantId, "ACME");
    seedKnownProductWithStockAndPrice(tenantId, "BP-100");
    long products = productRepository.count();
    long customers = customerAccountRepository.count();
    long inventory = inventorySnapshotRepository.count();
    long prices = priceRuleRepository.count();
    long quotes = draftQuoteRepository.count();
    long orders = draftOrderRepository.count();
    UUID erId = seedAdvisory(tenantId, "CHANNEL_MESSAGE", "SUCCEEDED",
        extraction("RFQ", "ACME", 0.82, List.of(lineItem("BP-100", "20", "PCS", 0.8))), List.of());

    service.validate(erId);

    assertThat(productRepository.count()).isEqualTo(products);
    assertThat(customerAccountRepository.count()).isEqualTo(customers);
    assertThat(inventorySnapshotRepository.count()).isEqualTo(inventory);
    assertThat(priceRuleRepository.count()).isEqualTo(prices);
    assertThat(draftQuoteRepository.count()).isEqualTo(quotes);
    assertThat(draftOrderRepository.count()).isEqualTo(orders);
  }

  private static List<String> issueCodes(AiValidationResultView result) {
    return result.issues().stream().map(i -> i.issueCode()).toList();
  }
}
