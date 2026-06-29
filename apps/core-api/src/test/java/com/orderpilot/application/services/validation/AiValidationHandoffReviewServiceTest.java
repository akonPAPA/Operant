package com.orderpilot.application.services.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.AiValidationDtos.AiValidationResultView;
import com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffCorrectionRequest;
import com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffDecisionRequest;
import com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffDraftPreparationCandidate;
import com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffReviewQueueItem;
import com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffReviewView;
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
 * OP-CAP-08B review queue + draft-preparation bridge tests: queue listing/filtering, review lifecycle
 * (start idempotency, decision rules), draft-preparation candidate gating, tenant fail-closed, and no
 * quote/order/master-data mutation. Validations/handoffs are produced by the real 07E/07F services.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    AiValidationHandoffReviewService.class,
    AiValidationHandoffService.class,
    ExtractionAdvisoryValidationService.class,
    ValidationEngineService.class,
    ProductIntelligenceService.class,
    AuditEventService.class,
    JsonSupport.class,
    CoreConfiguration.class,
    AiValidationHandoffReviewServiceTest.JacksonTestConfig.class
})
class AiValidationHandoffReviewServiceTest {
  @Autowired private AiValidationHandoffReviewService reviewService;
  @Autowired private AiValidationHandoffService handoffService;
  @Autowired private ExtractionAdvisoryValidationService validationService;
  @Autowired private AiValidationHandoffReviewRepository reviewRepository;
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
  private static final UUID TRUSTED_ACTOR =
      UUID.fromString("10000000-0000-0000-0000-000000000001");

  @TestConfiguration
  static class JacksonTestConfig {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper().findAndRegisterModules();
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

  /** Produce a real handoff via 07E + 07F and return its handoff view. */
  private AiValidationHandoffView handoff(UUID tenantId, String workerStatus, String customerHint, String qty) {
    UUID erId = seedAdvisory(tenantId, workerStatus,
        extraction(customerHint, 0.82, qty == null ? List.of() : List.of(lineItem("BP-100", qty))), List.of());
    AiValidationResultView v = validationService.validate(erId);
    return handoffService.generate(v.validationId());
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  // --- queue ---

  @Test
  void reviewQueueListsOnlyTenantScopedHandoffs() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    seedKnownCustomer(tenantA, "ACME");
    seedKnownProductWithStockAndPrice(tenantA, "BP-100");
    AiValidationHandoffView a = handoff(tenantA, "SUCCEEDED", "ACME", "20");

    TenantContext.setTenantId(tenantB);
    seedKnownCustomer(tenantB, "ACME");
    seedKnownProductWithStockAndPrice(tenantB, "BP-100");
    handoff(tenantB, "SUCCEEDED", "ACME", "20");

    TenantContext.setTenantId(tenantA);
    List<AiHandoffReviewQueueItem> queue = reviewService.queue(null, null, null, null, 50);

    assertThat(queue).hasSize(1);
    assertThat(queue.get(0).handoffId()).isEqualTo(a.handoffId());
    assertThat(queue.get(0).reviewStatus()).isEqualTo("PENDING_REVIEW");
  }

  @Test
  void reviewQueueFiltersByRoutingRiskAndReviewStatus() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownCustomer(tenantId, "ACME");
    seedKnownProductWithStockAndPrice(tenantId, "BP-100");
    AiValidationHandoffView ready = handoff(tenantId, "SUCCEEDED", "ACME", "20");
    AiValidationHandoffView blocked = handoff(tenantId, "SUCCEEDED", "ACME", "-5");

    assertThat(reviewService.queue(null, "READY_FOR_DRAFT_REVIEW", null, null, 50))
        .extracting(AiHandoffReviewQueueItem::handoffId).containsExactly(ready.handoffId());
    assertThat(reviewService.queue(null, null, "BLOCKED", null, 50))
        .extracting(AiHandoffReviewQueueItem::handoffId).containsExactly(blocked.handoffId());
    assertThat(reviewService.queue(null, null, null, true, 50))
        .extracting(AiHandoffReviewQueueItem::handoffId).containsExactly(ready.handoffId());

    reviewService.startReview(ready.handoffId(), TRUSTED_ACTOR);
    assertThat(reviewService.queue("IN_REVIEW", null, null, null, 50))
        .extracting(AiHandoffReviewQueueItem::handoffId).containsExactly(ready.handoffId());
    assertThat(reviewService.queue("PENDING_REVIEW", null, null, null, 50))
        .extracting(AiHandoffReviewQueueItem::handoffId).containsExactly(blocked.handoffId());
  }

  @Test
  void reviewDetailReturnsBoundedViewWithoutRawJson() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownCustomer(tenantId, "ACME");
    seedKnownProductWithStockAndPrice(tenantId, "BP-100");
    AiValidationHandoffView h = handoff(tenantId, "SUCCEEDED", "ACME", "20");

    AiHandoffReviewView detail = reviewService.get(h.handoffId());

    assertThat(detail.handoffId()).isEqualTo(h.handoffId());
    assertThat(detail.routingDecision()).isEqualTo("READY_FOR_DRAFT_REVIEW");
    assertThat(detail.reviewStatus()).isEqualTo("PENDING_REVIEW");
    assertThat(detail.externalExecution()).isEqualTo("DISABLED");
    // The DTO has no result_json field by construction; assert no raw content leaks via serialization.
    assertThat(writeJson(detail)).doesNotContain("result_json").doesNotContain("brake pads");
  }

  // --- lifecycle ---

  @Test
  void startReviewIsIdempotent() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownCustomer(tenantId, "ACME");
    seedKnownProductWithStockAndPrice(tenantId, "BP-100");
    AiValidationHandoffView h = handoff(tenantId, "SUCCEEDED", "ACME", "20");
    long before = countAudits("ai_validation_handoff_review.started");

    AiHandoffReviewView first =
        reviewService.startReview(h.handoffId(), TRUSTED_ACTOR);
    AiHandoffReviewView second =
        reviewService.startReview(h.handoffId(), TRUSTED_ACTOR);

    assertThat(first.reviewStatus()).isEqualTo("IN_REVIEW");
    assertThat(second.reviewStatus()).isEqualTo("IN_REVIEW");
    assertThat(second.reviewId()).isEqualTo(first.reviewId());
    assertThat(countAudits("ai_validation_handoff_review.started") - before).isEqualTo(1);
    assertThat(reviewRepository.count()).isEqualTo(1);
  }

  @Test
  void needsHumanReviewRequiresExplicitReasonToApproveForDraftPreparation() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownProductWithStockAndPrice(tenantId, "BP-100");
    AiValidationHandoffView h = handoff(tenantId, "SUCCEEDED", "NOPE", "20");
    assertThat(h.status()).isEqualTo("NEEDS_HUMAN_REVIEW");

    assertThatThrownBy(() -> reviewService.decide(h.handoffId(),
        new AiHandoffDecisionRequest("APPROVE_FOR_DRAFT_PREPARATION", null, null),
        TRUSTED_ACTOR))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("explicit reason");

    AiHandoffReviewView decided = reviewService.decide(h.handoffId(),
        new AiHandoffDecisionRequest(
            "APPROVE_FOR_DRAFT_PREPARATION",
            "OPERATOR_VERIFIED_CUSTOMER",
            "confirmed by phone"),
        TRUSTED_ACTOR);
    assertThat(decided.reviewStatus()).isEqualTo("DRAFT_PREPARATION_READY");
  }

  @Test
  void failedValidationCannotBecomeDraftPreparationReady() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    AiValidationHandoffView h = handoff(tenantId, "FAILED", null, null);
    assertThat(h.status()).isEqualTo("FAILED_VALIDATION");

    assertThatThrownBy(() -> reviewService.decide(h.handoffId(),
        new AiHandoffDecisionRequest("APPROVE_FOR_DRAFT_PREPARATION", "OVERRIDE", "note"),
        TRUSTED_ACTOR))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not draft eligible");
  }

  // --- draft-preparation candidate ---

  @Test
  void draftPreparationCandidateFailsBeforeApproval() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownCustomer(tenantId, "ACME");
    seedKnownProductWithStockAndPrice(tenantId, "BP-100");
    AiValidationHandoffView h = handoff(tenantId, "SUCCEEDED", "ACME", "20");

    assertThatThrownBy(() -> reviewService.draftPreparationCandidate(h.handoffId()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not draft-preparation-ready");
  }

  @Test
  void draftPreparationCandidateSucceedsAfterApprovalAndCreatesNoQuoteOrder() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownCustomer(tenantId, "ACME");
    seedKnownProductWithStockAndPrice(tenantId, "BP-100");
    AiValidationHandoffView h = handoff(tenantId, "SUCCEEDED", "ACME", "20");
    long quotes = draftQuoteRepository.count();
    long orders = draftOrderRepository.count();

    reviewService.decide(h.handoffId(),
        new AiHandoffDecisionRequest("APPROVE_FOR_DRAFT_PREPARATION", "VALIDATED", "ok"),
        TRUSTED_ACTOR);
    AiHandoffDraftPreparationCandidate candidate = reviewService.draftPreparationCandidate(h.handoffId());

    assertThat(candidate.draftPreparationAllowed()).isTrue();
    assertThat(candidate.handoffId()).isEqualTo(h.handoffId());
    assertThat(candidate.routingDecision()).isEqualTo("READY_FOR_DRAFT_REVIEW");
    assertThat(candidate.reviewDecision()).isEqualTo("APPROVE_FOR_DRAFT_PREPARATION");
    assertThat(candidate.externalExecution()).isEqualTo("DISABLED");
    assertThat(draftQuoteRepository.count()).isEqualTo(quotes);
    assertThat(draftOrderRepository.count()).isEqualTo(orders);
  }

  @Test
  void reviewQueueTenantMismatchFailsClosedOnDetail() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    seedKnownCustomer(tenantA, "ACME");
    seedKnownProductWithStockAndPrice(tenantA, "BP-100");
    AiValidationHandoffView h = handoff(tenantA, "SUCCEEDED", "ACME", "20");

    TenantContext.setTenantId(UUID.randomUUID());
    assertThatThrownBy(() -> reviewService.draftPreparationCandidate(h.handoffId()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not found for tenant");
  }

  @Test
  void reviewMutationsPersistAndAuditOnlyTrustedActorWithoutResponseLeak() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedKnownCustomer(tenantId, "ACME");
    seedKnownProductWithStockAndPrice(tenantId, "BP-100");
    AiValidationHandoffView started = handoff(tenantId, "SUCCEEDED", "ACME", "20");
    AiValidationHandoffView decided = handoff(tenantId, "SUCCEEDED", "ACME", "20");
    AiValidationHandoffView corrected = handoff(tenantId, "SUCCEEDED", "ACME", "20");

    AiHandoffReviewView startView =
        reviewService.startReview(started.handoffId(), TRUSTED_ACTOR);
    reviewService.decide(
        decided.handoffId(),
        new AiHandoffDecisionRequest(
            "APPROVE_FOR_DRAFT_PREPARATION", "VALIDATED_BY_OPERATOR", "verified"),
        TRUSTED_ACTOR);
    reviewService.recordCorrection(
        corrected.handoffId(),
        new AiHandoffCorrectionRequest("Corrected customer", "RFQ", "ACME", 1),
        TRUSTED_ACTOR);

    assertThat(reviewRepository.findByTenantIdAndHandoffId(tenantId, started.handoffId())
        .orElseThrow().getReviewedBy()).isEqualTo(TRUSTED_ACTOR.toString());
    assertThat(reviewRepository.findByTenantIdAndHandoffId(tenantId, decided.handoffId())
        .orElseThrow().getReviewedBy()).isEqualTo(TRUSTED_ACTOR.toString());
    assertThat(reviewRepository.findByTenantIdAndHandoffId(tenantId, corrected.handoffId())
        .orElseThrow().getReviewedBy()).isEqualTo(TRUSTED_ACTOR.toString());

    assertThat(auditEventRepository.findAll().stream()
        .filter(event -> event.getAction().startsWith("ai_validation_handoff_review."))
        .map(event -> event.getActorId()))
        .containsOnly(TRUSTED_ACTOR);
    assertThat(writeJson(startView))
        .doesNotContain("reviewedBy", "actorId", "actorUserId", TRUSTED_ACTOR.toString());
  }
}
