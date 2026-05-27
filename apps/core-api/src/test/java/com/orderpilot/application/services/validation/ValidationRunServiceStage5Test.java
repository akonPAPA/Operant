package com.orderpilot.application.services.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.orderpilot.domain.pricing.DiscountRule;
import com.orderpilot.domain.pricing.DiscountRuleRepository;
import com.orderpilot.domain.pricing.MarginRule;
import com.orderpilot.domain.pricing.MarginRuleRepository;
import com.orderpilot.domain.pricing.PriceRule;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductAlias;
import com.orderpilot.domain.product.ProductAliasRepository;
import com.orderpilot.domain.product.CustomerSubstitutionPreference;
import com.orderpilot.domain.product.CustomerSubstitutionPreferenceRepository;
import com.orderpilot.domain.product.OEMReference;
import com.orderpilot.domain.product.OEMReferenceRepository;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.product.ProductSubstitute;
import com.orderpilot.domain.product.ProductSubstituteRepository;
import com.orderpilot.domain.validation.ApprovalRequirementRepository;
import com.orderpilot.domain.validation.SubstituteCandidateRepository;
import com.orderpilot.domain.validation.ValidationIssueRepository;
import com.orderpilot.domain.validation.ValidationRun;
import com.orderpilot.domain.validation.ValidationRunRepository;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
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
class ValidationRunServiceStage5Test {
  private static final Instant NOW = Instant.parse("2026-05-23T00:00:00Z");

  @Autowired private ValidationRunService service;
  @Autowired private ExtractionResultRepository extractionResults;
  @Autowired private ExtractedFieldRepository fields;
  @Autowired private ExtractedLineItemRepository lines;
  @Autowired private CustomerAccountRepository customers;
  @Autowired private ProductRepository products;
  @Autowired private ProductAliasRepository aliases;
  @Autowired private OEMReferenceRepository oemReferences;
  @Autowired private ProductSubstituteRepository substitutes;
  @Autowired private CustomerSubstitutionPreferenceRepository substitutionPreferences;
  @Autowired private LocationRepository locations;
  @Autowired private InventorySnapshotRepository inventory;
  @Autowired private PriceRuleRepository prices;
  @Autowired private DiscountRuleRepository discounts;
  @Autowired private MarginRuleRepository margins;
  @Autowired private ValidationIssueRepository issues;
  @Autowired private ApprovalRequirementRepository approvals;
  @Autowired private SubstituteCandidateRepository substituteCandidates;
  @Autowired private ValidationRunRepository validationRuns;
  @Autowired private DraftQuoteRepository draftQuotes;
  @Autowired private DraftOrderRepository draftOrders;
  @Autowired private ConnectorCommandRepository connectorCommands;
  @Autowired private ChangeRequestRepository changeRequests;
  @Autowired private AuditEventRepository auditEvents;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void validExtractionPassesWithoutBusinessMutations() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product product = product(tenantId, "SKU-OK", "Filter", "10");
    Location location = locations.save(new Location(tenantId, "MAIN", "Main", "WAREHOUSE", null, "Almaty", "KZ", true, NOW));
    customer(tenantId, "ACME", "Acme", location.getId());
    inventory.save(new InventorySnapshot(tenantId, product.getId(), location.getId(), new BigDecimal("20"), new BigDecimal("20"), BigDecimal.ZERO, NOW, "TEST", null, NOW));
    prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    ExtractionResult result = extraction(tenantId, "0.90", "ready_for_validation", "{}");
    fields.save(new ExtractedField(tenantId, result.getId(), "customer_hint", "Acme", "Acme", "customer_hint", new BigDecimal("0.90"), null, NOW));
    lines.save(new ExtractedLineItem(tenantId, result.getId(), 1, "SKU-OK", "Filter", "2", new BigDecimal("2"), "EA", "EA", new BigDecimal("0.90"), null, NOW));
    long productCount = products.count();
    long customerCount = customers.count();
    long inventoryCount = inventory.count();
    long priceCount = prices.count();
    long draftQuoteCount = draftQuotes.count();
    long draftOrderCount = draftOrders.count();
    long connectorCommandCount = connectorCommands.count();
    long changeRequestCount = changeRequests.count();

    ValidationRun run = service.run(result.getId(), "FULL");

    assertThat(run.getOverallStatus()).isEqualTo("VALIDATION_PASSED");
    assertThat(issues.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantId, run.getId())).isEmpty();
    assertThat(products.count()).isEqualTo(productCount);
    assertThat(customers.count()).isEqualTo(customerCount);
    assertThat(inventory.count()).isEqualTo(inventoryCount);
    assertThat(prices.count()).isEqualTo(priceCount);
    assertThat(draftQuotes.count()).isEqualTo(draftQuoteCount);
    assertThat(draftOrders.count()).isEqualTo(draftOrderCount);
    assertThat(connectorCommands.count()).isEqualTo(connectorCommandCount);
    assertThat(changeRequests.count()).isEqualTo(changeRequestCount);
    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenantId)).extracting("action").contains("VALIDATION_RUN_STARTED", "VALIDATION_RUN_COMPLETED");
  }

  @Test
  void unknownSkuCreatesProductNotFoundIssue() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ExtractionResult result = extractionWithCustomerAndLine(tenantId, "UNKNOWN-SKU", "2", "EA", "0.90");

    ValidationRun run = service.run(result.getId(), "FULL");

    assertThat(run.getOverallStatus()).isEqualTo("NEEDS_REVIEW");
    assertThat(issueTypes(tenantId, run.getId())).contains("PRODUCT_NOT_FOUND");
  }

  @Test
  void aliasMatchCreatesProductAliasMatchedIssue() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product product = product(tenantId, "SKU-A", "Filter", "10");
    aliases.save(new ProductAlias(tenantId, product.getId(), "CUSTOMER", "ALIAS-A", "ALIAS-A", null, new BigDecimal("0.90"), NOW));
    ExtractionResult result = extractionWithCustomerAndLine(tenantId, "ALIAS-A", "2", "EA", "0.90");

    ValidationRun run = service.run(result.getId(), "FULL");

    assertThat(issueTypes(tenantId, run.getId())).contains("PRODUCT_ALIAS_MATCHED");
  }

  @Test
  void oemReferenceMatchCreatesOemMatchedIssue() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product product = product(tenantId, "SKU-OEM", "OEM Filter", "10");
    oemReferences.save(new OEMReference(tenantId, product.getId(), "OEM 123", "OEM 123", "OEMCO", NOW));
    prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    ExtractionResult result = extractionWithCustomerAndLine(tenantId, "OEM 123", "2", "EA", "0.90");

    ValidationRun run = service.run(result.getId(), "FULL");

    assertThat(issueTypes(tenantId, run.getId())).contains("OEM_MATCHED");
  }

  @Test
  void unknownUomCreatesReviewIssueAndApprovalRequirement() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product product = product(tenantId, "SKU-UOM", "Uom Filter", "10");
    prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    ExtractionResult result = extractionWithCustomerAndLine(tenantId, "SKU-UOM", "2", "PALLET", "0.90");

    ValidationRun run = service.run(result.getId(), "FULL");

    assertThat(issueTypes(tenantId, run.getId())).contains("INVALID_UOM");
    assertThat(approvals.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantId, run.getId()))
        .extracting("requirementType")
        .contains("INVALID_UOM_REQUIRES_REVIEW");
  }

  @Test
  void outOfStockCreatesIssueAndSuggestsUnapprovedSubstitute() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product source = product(tenantId, "SKU-OOS", "Source", "10");
    Product substitute = product(tenantId, "SKU-SUB", "Substitute", "12");
    substitutes.save(new ProductSubstitute(tenantId, source.getId(), substitute.getId(), "FUNCTIONAL", "LOW", false, "same family", NOW));
    Location location = locations.save(new Location(tenantId, "OOS", "OOS", "WAREHOUSE", null, "Almaty", "KZ", true, NOW));
    customer(tenantId, "OOS-C", "Oos Customer", location.getId());
    inventory.save(new InventorySnapshot(tenantId, source.getId(), location.getId(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, NOW, "TEST", null, NOW));
    prices.save(new PriceRule(tenantId, source.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    ExtractionResult result = extractionWithCustomerAndLine(tenantId, "SKU-OOS", "2", "EA", "0.90");

    ValidationRun run = service.run(result.getId(), "FULL");

    assertThat(issueTypes(tenantId, run.getId())).contains("OUT_OF_STOCK", "SUBSTITUTE_AVAILABLE");
    assertThat(substituteCandidates.findByTenantIdAndValidationRunIdOrderByRankScoreDesc(tenantId, run.getId()))
        .singleElement()
        .satisfies(candidate -> {
          assertThat(candidate.getSubstituteProductId()).isEqualTo(substitute.getId());
          assertThat(candidate.getStatus()).isEqualTo("CANDIDATE");
        });
  }

  @Test
  void blockedSubstituteIsNotMarkedSafe() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product source = product(tenantId, "SKU-BLOCKED", "Source", "10");
    Product substitute = product(tenantId, "SKU-BLOCKED-SUB", "Blocked Substitute", "12");
    substitutes.save(new ProductSubstitute(tenantId, source.getId(), substitute.getId(), "FUNCTIONAL", "LOW", false, "same family", NOW));
    Location location = locations.save(new Location(tenantId, "BLOCK", "Block", "WAREHOUSE", null, "Almaty", "KZ", true, NOW));
    CustomerAccount customer = customer(tenantId, "BLOCKED-BUYER", "Blocked Buyer", location.getId());
    substitutionPreferences.save(new CustomerSubstitutionPreference(tenantId, customer.getId(), source.getId(), null, true, true, substitute.getId(), "customer blocked this substitute", NOW));
    inventory.save(new InventorySnapshot(tenantId, source.getId(), location.getId(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, NOW, "TEST", null, NOW));
    inventory.save(new InventorySnapshot(tenantId, substitute.getId(), location.getId(), new BigDecimal("10"), new BigDecimal("10"), BigDecimal.ZERO, NOW, "TEST", null, NOW));
    prices.save(new PriceRule(tenantId, source.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    ExtractionResult result = extraction(tenantId, "0.90", "ready_for_validation", "{}");
    fields.save(new ExtractedField(tenantId, result.getId(), "customer_hint", "Blocked Buyer", "Blocked Buyer", "customer_hint", new BigDecimal("0.90"), null, NOW));
    lines.save(new ExtractedLineItem(tenantId, result.getId(), 1, "SKU-BLOCKED", "Source", "2", new BigDecimal("2"), "EA", "EA", new BigDecimal("0.90"), null, NOW));

    ValidationRun run = service.run(result.getId(), "FULL");

    assertThat(substituteCandidates.findByTenantIdAndValidationRunIdOrderByRankScoreDesc(tenantId, run.getId()))
        .singleElement()
        .satisfies(candidate -> {
          assertThat(candidate.getSubstituteProductId()).isEqualTo(substitute.getId());
          assertThat(candidate.getStatus()).isEqualTo("BLOCKED_BY_CUSTOMER_POLICY");
          assertThat(candidate.isRequiresApproval()).isTrue();
        });
  }

  @Test
  void lowConfidenceCreatesHumanReviewRequirement() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ExtractionResult result = extractionWithCustomerAndLine(tenantId, "LOW-CONF", "2", "EA", "0.30");

    ValidationRun run = service.run(result.getId(), "FULL");

    assertThat(issueTypes(tenantId, run.getId())).contains("LOW_EXTRACTION_CONFIDENCE");
    assertThat(approvals.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantId, run.getId()))
        .extracting("requirementType")
        .contains("NEEDS_HUMAN_REVIEW");
  }

  @Test
  void discountAndMarginGuardrailsCreateApprovalRequirement() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product product = product(tenantId, "SKU-RISK", "Risk", "95");
    discounts.save(new DiscountRule(tenantId, "D1", "Max 5", null, null, product.getId(), new BigDecimal("5"), new BigDecimal("5"), NOW.minusSeconds(60), null, NOW));
    margins.save(new MarginRule(tenantId, "M1", "Min margin", product.getId(), null, null, new BigDecimal("20"), new BigDecimal("30"), NOW));
    prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("100"), "USD", NOW.minusSeconds(60), null, 100, NOW));
    ExtractionResult result = extractionWithCustomerAndLine(tenantId, "SKU-RISK", "2", "EA", "0.90", "{\"discountPercent\":\"20\"}");

    ValidationRun run = service.run(result.getId(), "FULL");

    assertThat(approvals.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantId, run.getId()))
        .extracting("requirementType")
        .contains("DISCOUNT_REQUIRES_APPROVAL", "MARGIN_BELOW_GUARDRAIL");
  }

  @Test
  void tenantIsolationPreventsCrossTenantValidation() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    ExtractionResult result = extractionWithCustomerAndLine(tenantA, "SKU-A", "2", "EA", "0.90");
    TenantContext.setTenantId(UUID.randomUUID());

    assertThatThrownBy(() -> service.run(result.getId(), "FULL")).isInstanceOf(RuntimeException.class);
    assertThat(validationRuns.findByTenantIdAndExtractionResultIdOrderByCreatedAtDesc(tenantA, result.getId())).isEmpty();
  }

  private ExtractionResult extractionWithCustomerAndLine(UUID tenantId, String sku, String quantity, String uom, String confidence) {
    return extractionWithCustomerAndLine(tenantId, sku, quantity, uom, confidence, "{}");
  }

  private ExtractionResult extractionWithCustomerAndLine(UUID tenantId, String sku, String quantity, String uom, String confidence, String resultJson) {
    customer(tenantId, "ACME-" + sku, "Acme " + sku, null);
    ExtractionResult result = extraction(tenantId, confidence, new BigDecimal(confidence).compareTo(new BigDecimal("0.50")) < 0 ? "needs_review" : "ready_for_validation", resultJson);
    fields.save(new ExtractedField(tenantId, result.getId(), "customer_hint", "Acme " + sku, "Acme " + sku, "customer_hint", new BigDecimal(confidence), null, NOW));
    lines.save(new ExtractedLineItem(tenantId, result.getId(), 1, sku, "Requested " + sku, quantity, new BigDecimal(quantity), uom, uom, new BigDecimal(confidence), null, NOW));
    return result;
  }

  private ExtractionResult extraction(UUID tenantId, String confidence, String validationStatus, String resultJson) {
    return extractionResults.save(new ExtractionResult(tenantId, UUID.randomUUID(), "CHANNEL_MESSAGE", UUID.randomUUID(), "RFQ", "message", new BigDecimal(confidence), resultJson, validationStatus, NOW));
  }

  private CustomerAccount customer(UUID tenantId, String accountCode, String name, UUID defaultLocationId) {
    return customers.save(new CustomerAccount(tenantId, null, accountCode, name, name, null, "ACTIVE", "USD", defaultLocationId, NOW));
  }

  private Product product(UUID tenantId, String sku, String name, String cost) {
    return products.save(new Product(tenantId, sku, name, name, "Filters", "Brand", "Maker", "EA", "ACTIVE", new BigDecimal(cost), "USD", NOW));
  }

  private List<String> issueTypes(UUID tenantId, UUID runId) {
    return issues.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantId, runId).stream().map(issue -> issue.getIssueType()).toList();
  }
}
