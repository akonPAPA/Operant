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
import com.orderpilot.domain.integration.ChangeRequestRepository;
import com.orderpilot.domain.integration.ConnectorCommandRepository;
import com.orderpilot.domain.inventory.InventorySnapshot;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.location.Location;
import com.orderpilot.domain.location.LocationRepository;
import com.orderpilot.domain.pricing.PriceRule;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.product.ProductSubstitute;
import com.orderpilot.domain.product.ProductSubstituteRepository;
import com.orderpilot.domain.validation.ValidationRun;
import com.orderpilot.application.services.validation.ValidationRunService;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.domain.workspace.ExceptionCase;
import com.orderpilot.domain.workspace.ExceptionCaseRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class OperatorReviewServiceStage6Test {
  private static final Instant NOW = Instant.parse("2026-05-23T00:00:00Z");

  @Autowired private ValidationRunService validationRunService;
  @Autowired private OperatorReviewService reviewService;
  @Autowired private ValidationReviewService validationReviewService;
  @Autowired private DraftCommandPreparationService draftCommandPreparationService;
  @Autowired private ExtractionResultRepository extractionResults;
  @Autowired private ExtractedFieldRepository fields;
  @Autowired private ExtractedLineItemRepository lines;
  @Autowired private CustomerAccountRepository customers;
  @Autowired private ProductRepository products;
  @Autowired private ProductSubstituteRepository substitutes;
  @Autowired private LocationRepository locations;
  @Autowired private InventorySnapshotRepository inventory;
  @Autowired private PriceRuleRepository prices;
  @Autowired private DraftQuoteRepository draftQuotes;
  @Autowired private DraftOrderRepository draftOrders;
  @Autowired private ConnectorCommandRepository connectorCommands;
  @Autowired private ChangeRequestRepository changeRequests;
  @Autowired private AuditEventRepository auditEvents;
  @Autowired private ExceptionCaseRepository exceptionCases;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void reviewCaseGroupsIssuesAndSuggestionsWithoutBusinessWrites() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product source = product(tenantId, "SKU-OOS", "Source", "10");
    Product substitute = product(tenantId, "SKU-SUB", "Substitute", "12");
    substitutes.save(new ProductSubstitute(tenantId, source.getId(), substitute.getId(), "FUNCTIONAL", "LOW", false, "same family", NOW));
    Location location = locations.save(new Location(tenantId, "MAIN", "Main", "WAREHOUSE", null, "Almaty", "KZ", true, NOW));
    customer(tenantId, "ACME", "Acme", location.getId());
    inventory.save(new InventorySnapshot(tenantId, source.getId(), location.getId(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, NOW, "TEST", null, NOW));
    prices.save(new PriceRule(tenantId, source.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    ExtractionResult extraction = extraction(tenantId, "0.40", "needs_review");
    fields.save(new ExtractedField(tenantId, extraction.getId(), "customer_hint", "Acme", "Acme", "customer_hint", new BigDecimal("0.40"), null, NOW));
    lines.save(new ExtractedLineItem(tenantId, extraction.getId(), 1, "SKU-OOS", "Source", "2", new BigDecimal("2"), "EA", "EA", new BigDecimal("0.40"), null, NOW));
    ValidationRun run = validationRunService.run(extraction.getId(), "FULL");
    long quoteCount = draftQuotes.count();
    long orderCount = draftOrders.count();
    long connectorCount = connectorCommands.count();
    long changeRequestCount = changeRequests.count();

    ReviewCaseDetail detail = reviewService.createForValidationRun(run.getId());

    assertThat(detail.reviewCase().status()).isEqualTo("REVIEW_REQUIRED");
    assertThat(detail.issueGroups()).extracting("group").contains("DOCUMENT", "INVENTORY", "SUBSTITUTION_COMPATIBILITY");
    assertThat(detail.issueGroups().stream().flatMap(group -> group.issues().stream()).map(issue -> issue.issueType()))
        .contains("LOW_EXTRACTION_CONFIDENCE", "OUT_OF_STOCK", "SUBSTITUTE_AVAILABLE");
    assertThat(detail.approvalRequirements()).extracting("requirementType").contains("NEEDS_HUMAN_REVIEW");
    assertThat(detail.suggestedActions()).extracting("actionType").contains("REQUEST_MANAGER_APPROVAL", "SELECT_SUBSTITUTE_CANDIDATE");
    assertThat(detail.substituteCandidates()).singleElement().satisfies(candidate -> {
      assertThat(candidate.substituteProductId()).isEqualTo(substitute.getId());
      assertThat(candidate.status()).isEqualTo("CANDIDATE");
    });
    assertThat(draftQuotes.count()).isEqualTo(quoteCount);
    assertThat(draftOrders.count()).isEqualTo(orderCount);
    assertThat(connectorCommands.count()).isEqualTo(connectorCount);
    assertThat(changeRequests.count()).isEqualTo(changeRequestCount);
  }

  @Test
  void operatorActionsAreAuditedAndApprovalDoesNotCreateDownstreamRecords() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ExtractionResult extraction = extractionWithUnknownSku(tenantId);
    ValidationRun run = validationRunService.run(extraction.getId(), "FULL");
    ReviewCaseDetail created = reviewService.createForValidationRun(run.getId());
    long quoteCount = draftQuotes.count();
    long orderCount = draftOrders.count();
    long connectorCount = connectorCommands.count();
    long changeRequestCount = changeRequests.count();

    reviewService.startReview(created.reviewCase().id(), null);
    reviewService.addNote(created.reviewCase().id(), "Check with sales ops", null);
    ReviewCaseDetail approved = reviewService.approveForNextStep(created.reviewCase().id(), null);

    assertThat(approved.reviewCase().status()).isEqualTo("APPROVED_FOR_NEXT_STEP");
    assertThat(draftQuotes.count()).isEqualTo(quoteCount);
    assertThat(draftOrders.count()).isEqualTo(orderCount);
    assertThat(connectorCommands.count()).isEqualTo(connectorCount);
    assertThat(changeRequests.count()).isEqualTo(changeRequestCount);
    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenantId)).extracting("action")
        .contains("REVIEW_CASE_CREATED", "REVIEW_STARTED", "INTERNAL_NOTE_ADDED", "REVIEW_APPROVED_FOR_NEXT_STEP");
  }

  @Test
  void tenantIsolationPreventsCrossTenantReviewAccess() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    ValidationRun run = validationRunService.run(extractionWithUnknownSku(tenantA).getId(), "FULL");
    ReviewCaseDetail created = reviewService.createForValidationRun(run.getId());
    TenantContext.setTenantId(UUID.randomUUID());

    assertThatThrownBy(() -> reviewService.detail(created.reviewCase().id())).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> reviewService.createForValidationRun(run.getId())).isInstanceOf(RuntimeException.class);
  }

  @Test
  void botOriginatedExceptionCaseIsNotValidationBackedAndCannotPrepareDrafts() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ExceptionCase botCase = exceptionCases.save(new ExceptionCase(
        tenantId,
        "BOT-TEST",
        "BOT_CONVERSATION",
        UUID.randomUUID(),
        null,
        null,
        null,
        "Bot operator handoff",
        "OPEN",
        "NORMAL",
        "INFO",
        "Bot-only handoff without validation backing",
        NOW));
    long quoteCount = draftQuotes.count();
    long orderCount = draftOrders.count();
    long connectorCount = connectorCommands.count();
    long inventoryCount = inventory.count();

    assertThatThrownBy(() -> reviewService.detail(botCase.getId()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not validation-backed")
        .hasMessageContaining("BOT_CONVERSATION");
    assertThatThrownBy(() -> validationReviewService.get(botCase.getId()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not validation-backed");
    assertThat(draftCommandPreparationService.preview(botCase.getId(), "QUOTE", null).draftPreparationAllowed()).isFalse();
    assertThat(draftCommandPreparationService.preview(botCase.getId(), "QUOTE", null).blockingReasons())
        .extracting("issueCode")
        .contains("BOT_HANDOFF_NOT_VALIDATION_BACKED");
    assertThatThrownBy(() -> draftCommandPreparationService.prepareDraftQuote(botCase.getId(), null))
        .isInstanceOf(DraftPreparationBlockedException.class);
    assertThatThrownBy(() -> draftCommandPreparationService.prepareDraftOrder(botCase.getId(), null))
        .isInstanceOf(DraftPreparationBlockedException.class);
    assertThat(draftQuotes.count()).isEqualTo(quoteCount);
    assertThat(draftOrders.count()).isEqualTo(orderCount);
    assertThat(connectorCommands.count()).isEqualTo(connectorCount);
    assertThat(inventory.count()).isEqualTo(inventoryCount);
    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenantId)).extracting("action")
        .contains("DRAFT_PREPARATION_BLOCKED");
  }

  private ExtractionResult extractionWithUnknownSku(UUID tenantId) {
    customer(tenantId, "ACME-UNKNOWN", "Acme Unknown", null);
    ExtractionResult result = extraction(tenantId, "0.90", "ready_for_validation");
    fields.save(new ExtractedField(tenantId, result.getId(), "customer_hint", "Acme Unknown", "Acme Unknown", "customer_hint", new BigDecimal("0.90"), null, NOW));
    lines.save(new ExtractedLineItem(tenantId, result.getId(), 1, "UNKNOWN", "Requested unknown", "2", new BigDecimal("2"), "EA", "EA", new BigDecimal("0.90"), null, NOW));
    return result;
  }

  private ExtractionResult extraction(UUID tenantId, String confidence, String validationStatus) {
    return extractionResults.save(new ExtractionResult(tenantId, UUID.randomUUID(), "CHANNEL_MESSAGE", UUID.randomUUID(), "RFQ", "message", new BigDecimal(confidence), "{}", validationStatus, NOW));
  }

  private CustomerAccount customer(UUID tenantId, String accountCode, String name, UUID defaultLocationId) {
    return customers.save(new CustomerAccount(tenantId, null, accountCode, name, name, null, "ACTIVE", "USD", defaultLocationId, NOW));
  }

  private Product product(UUID tenantId, String sku, String name, String cost) {
    return products.save(new Product(tenantId, sku, name, name, "Filters", "Brand", "Maker", "EA", "ACTIVE", new BigDecimal(cost), "USD", NOW));
  }
}
