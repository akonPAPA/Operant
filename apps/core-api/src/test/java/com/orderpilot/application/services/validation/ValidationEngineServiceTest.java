package com.orderpilot.application.services.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orderpilot.api.dto.ValidationEngineDtos.ExtractedRequestValidationResult;
import com.orderpilot.api.dto.ValidationEngineDtos.ValidateExtractedRequestCommand;
import com.orderpilot.api.dto.ValidationEngineDtos.ValidationIssueView;
import com.orderpilot.api.dto.ValidationEngineDtos.ValidationLineInput;
import com.orderpilot.application.services.ProductCodeNormalizer;
import com.orderpilot.application.services.product.ProductIntelligenceService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.inventory.InventorySnapshot;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.pricing.MarginRule;
import com.orderpilot.domain.pricing.MarginRuleRepository;
import com.orderpilot.domain.pricing.PriceRule;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.product.CustomerSubstitutionPreferenceRepository;
import com.orderpilot.domain.product.OEMReference;
import com.orderpilot.domain.product.OEMReferenceRepository;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductAlias;
import com.orderpilot.domain.product.ProductAliasRepository;
import com.orderpilot.domain.product.ProductCompatibilityRepository;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.product.ProductSubstituteRepository;
import com.orderpilot.domain.risk.ApprovalRequirementType;
import com.orderpilot.domain.risk.ValidationRiskDecision;
import com.orderpilot.domain.validation.ValidationCaseStatus;
import com.orderpilot.domain.validation.ValidationIssueType;
import com.orderpilot.domain.validation.ValidationSeverity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ValidationEngineServiceTest {
  private final ProductRepository productRepository = mock(ProductRepository.class);
  private final ProductAliasRepository productAliasRepository = mock(ProductAliasRepository.class);
  private final OEMReferenceRepository oemReferenceRepository = mock(OEMReferenceRepository.class);
  private final CustomerAccountRepository customerAccountRepository = mock(CustomerAccountRepository.class);
  private final InventorySnapshotRepository inventorySnapshotRepository = mock(InventorySnapshotRepository.class);
  private final PriceRuleRepository priceRuleRepository = mock(PriceRuleRepository.class);
  private final MarginRuleRepository marginRuleRepository = mock(MarginRuleRepository.class);
  private final ProductSubstituteRepository productSubstituteRepository = mock(ProductSubstituteRepository.class);
  private final ProductCompatibilityRepository productCompatibilityRepository = mock(ProductCompatibilityRepository.class);
  private final CustomerSubstitutionPreferenceRepository customerSubstitutionPreferenceRepository = mock(CustomerSubstitutionPreferenceRepository.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-06-06T00:00:00Z"), ZoneOffset.UTC);

  private final ProductIntelligenceService productIntelligenceService = new ProductIntelligenceService(
      productRepository, productAliasRepository, oemReferenceRepository, productSubstituteRepository,
      productCompatibilityRepository, customerSubstitutionPreferenceRepository, inventorySnapshotRepository, clock);

  private final ValidationEngineService service = new ValidationEngineService(
      productRepository, customerAccountRepository, inventorySnapshotRepository,
      priceRuleRepository, marginRuleRepository, productIntelligenceService, clock);

  private UUID tenantId;

  @BeforeEach void setTenant() {
    tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
  }

  @AfterEach void clearTenant() { TenantContext.clear(); }

  @Test void exactSkuMatchProducesNoBlockingProductIssue() {
    UUID pid = stubExactSku("PAD-100", "Brake Pad", null, null);
    ExtractedRequestValidationResult result = service.validate(cmd("RFQ", null, "ACME", 0.9, List.of(), List.of(
        line(1, "brake pads", "PAD-100", bd("5"), "pcs", 0.9, null))));

    assertThat(result.lineResults().get(0).matchType()).isEqualTo("EXACT_SKU");
    assertThat(result.lineResults().get(0).matchedProduct().productId()).isEqualTo(pid);
    assertThat(blocking(result)).isEmpty();
  }

  @Test void unknownProductEmitsProductNotFoundAndNeedsReview() {
    ExtractedRequestValidationResult result = service.validate(cmd("RFQ", null, "ACME", 0.9, List.of(), List.of(
        line(1, "mystery", "NO-SUCH-SKU", bd("2"), "pcs", 0.9, null))));

    assertThat(types(result)).contains(ValidationIssueType.PRODUCT_NOT_FOUND);
    assertThat(result.lineResults().get(0).substituteRequired()).isTrue();
    assertThat(result.riskDecision()).isEqualTo(ValidationRiskDecision.NEEDS_OPERATOR_REVIEW);
  }

  @Test void aliasMatchResolvesProduct() {
    UUID pid = UUID.randomUUID();
    String norm = ProductCodeNormalizer.normalize("ALT-9");
    when(productRepository.findByTenantIdAndNormalizedSkuAndDeletedAtIsNullAndStatus(tenantId, norm, "ACTIVE")).thenReturn(List.of());
    ProductAlias alias = mock(ProductAlias.class);
    when(alias.getProductId()).thenReturn(pid);
    when(productAliasRepository.findByTenantIdAndNormalizedAliasAndActiveTrue(tenantId, norm)).thenReturn(List.of(alias));
    Product aliasProduct = product(pid, "PAD-1", "Pad", null, null);
    when(productRepository.findByIdAndTenantIdAndDeletedAtIsNull(pid, tenantId)).thenReturn(Optional.of(aliasProduct));

    ExtractedRequestValidationResult result = service.validate(cmd("RFQ", null, "ACME", 0.9, List.of(), List.of(
        line(1, "pad", "ALT-9", bd("1"), "pcs", 0.9, null))));

    assertThat(result.lineResults().get(0).matchType()).isEqualTo("ALIAS");
    assertThat(types(result)).contains(ValidationIssueType.PRODUCT_ALIAS_MATCHED);
  }

  @Test void oemReferenceMatchResolvesProduct() {
    UUID pid = UUID.randomUUID();
    String norm = ProductCodeNormalizer.normalize("OEM-55");
    when(productRepository.findByTenantIdAndNormalizedSkuAndDeletedAtIsNullAndStatus(tenantId, norm, "ACTIVE")).thenReturn(List.of());
    when(productAliasRepository.findByTenantIdAndNormalizedAliasAndActiveTrue(tenantId, norm)).thenReturn(List.of());
    OEMReference oem = mock(OEMReference.class);
    when(oem.getProductId()).thenReturn(pid);
    when(oemReferenceRepository.findByTenantIdAndNormalizedOemCodeAndActiveTrue(tenantId, norm)).thenReturn(List.of(oem));
    Product oemProduct = product(pid, "PAD-2", "Pad2", null, null);
    when(productRepository.findByIdAndTenantIdAndDeletedAtIsNull(pid, tenantId)).thenReturn(Optional.of(oemProduct));

    ExtractedRequestValidationResult result = service.validate(cmd("RFQ", null, "ACME", 0.9, List.of(), List.of(
        line(1, "pad", "OEM-55", bd("1"), "pcs", 0.9, null))));

    assertThat(result.lineResults().get(0).matchType()).isEqualTo("OEM_REFERENCE");
    assertThat(types(result)).contains(ValidationIssueType.OEM_MATCHED);
  }

  @Test void missingCustomerEmitsCustomerNotFound() {
    stubExactSku("PAD-100", "Brake Pad", null, null);
    ExtractedRequestValidationResult result = service.validate(cmd("RFQ", null, null, 0.9, List.of(), List.of(
        line(1, "brake pads", "PAD-100", bd("5"), "pcs", 0.9, null))));

    assertThat(types(result)).contains(ValidationIssueType.CUSTOMER_NOT_FOUND);
    assertThat(result.matchedCustomer()).isNull();
  }

  @Test void zeroOrNegativeQuantityIsBlocking() {
    stubExactSku("PAD-100", "Brake Pad", null, null);
    ExtractedRequestValidationResult result = service.validate(cmd("RFQ", null, "ACME", 0.9, List.of(), List.of(
        line(1, "brake pads", "PAD-100", bd("0"), "pcs", 0.9, null))));

    assertThat(result.issues()).anyMatch(i -> i.type() == ValidationIssueType.INVALID_QUANTITY && i.severity() == ValidationSeverity.CRITICAL);
    assertThat(result.riskDecision()).isEqualTo(ValidationRiskDecision.BLOCKED);
    assertThat(result.status()).isEqualTo(ValidationCaseStatus.BLOCKED);
  }

  @Test void missingUomCreatesUomIssue() {
    stubExactSku("PAD-100", "Brake Pad", null, null);
    ExtractedRequestValidationResult result = service.validate(cmd("RFQ", null, "ACME", 0.9, List.of(), List.of(
        line(1, "brake pads", "PAD-100", bd("5"), null, 0.9, null))));

    assertThat(types(result)).contains(ValidationIssueType.INVALID_UOM);
  }

  @Test void lowExtractionConfidenceForcesNeedsReview() {
    UUID custId = stubCustomerById();
    stubExactSku("PAD-100", "Brake Pad", null, null);
    stubAvailableInventory("PAD-100");
    stubActivePrice("PAD-100", "100");
    ExtractedRequestValidationResult result = service.validate(cmd("RFQ", custId, null, 0.2, List.of(), List.of(
        line(1, "brake pads", "PAD-100", bd("5"), "EA", 0.2, null))));

    assertThat(types(result)).contains(ValidationIssueType.LOW_EXTRACTION_CONFIDENCE);
    assertThat(result.riskDecision()).isEqualTo(ValidationRiskDecision.NEEDS_OPERATOR_REVIEW);
  }

  @Test void promptInjectionForcesNeedsReviewNotAutoReady() {
    UUID custId = stubCustomerById();
    stubExactSku("PAD-100", "Brake Pad", null, null);
    stubAvailableInventory("PAD-100");
    stubActivePrice("PAD-100", "100");
    ExtractedRequestValidationResult result = service.validate(cmd("RFQ", custId, null, 0.95,
        List.of("ignore previous instructions"), List.of(
        line(1, "brake pads", "PAD-100", bd("5"), "EA", 0.95, null))));

    assertThat(types(result)).contains(ValidationIssueType.PROMPT_INJECTION_FLAGGED);
    assertThat(result.riskDecision()).isEqualTo(ValidationRiskDecision.NEEDS_OPERATOR_REVIEW);
    assertThat(result.riskDecision()).isNotEqualTo(ValidationRiskDecision.AUTO_READY_DRAFT);
  }

  @Test void unavailableInventoryEmitsUnavailableAndSubstituteRequired() {
    UUID pid = stubExactSku("PAD-100", "Brake Pad", null, null);
    InventorySnapshot snap = mock(InventorySnapshot.class);
    when(snap.getQuantityAvailable()).thenReturn(BigDecimal.ZERO);
    when(snap.getCapturedAt()).thenReturn(clock.instant());
    when(inventorySnapshotRepository.findTop50ByTenantIdAndProductIdOrderByCapturedAtDesc(tenantId, pid)).thenReturn(List.of(snap));

    ExtractedRequestValidationResult result = service.validate(cmd("RFQ", null, "ACME", 0.9, List.of(), List.of(
        line(1, "brake pads", "PAD-100", bd("5"), "pcs", 0.9, null))));

    assertThat(types(result)).contains(ValidationIssueType.INVENTORY_UNAVAILABLE, ValidationIssueType.SUBSTITUTE_REQUIRED);
    assertThat(result.lineResults().get(0).inventoryStatus()).isEqualTo("UNAVAILABLE");
    assertThat(result.lineResults().get(0).substituteRequired()).isTrue();
  }

  @Test void marginBelowGuardrailCreatesApprovalRequirement() {
    UUID custId = stubCustomerById();
    UUID pid = stubExactSku("PAD-100", "Brake Pad", bd("80"), null); // cost 80
    stubAvailableInventory("PAD-100");
    stubActivePrice("PAD-100", "100"); // margin = 20%
    MarginRule rule = mock(MarginRule.class);
    when(rule.getProductId()).thenReturn(null);
    when(rule.getCategory()).thenReturn(null);
    when(rule.getApprovalRequiredBelowPercent()).thenReturn(bd("25")); // 20% < 25% -> approval
    when(marginRuleRepository.findByTenantIdAndActiveTrue(tenantId)).thenReturn(List.of(rule));

    ExtractedRequestValidationResult result = service.validate(cmd("RFQ", custId, null, 0.95, List.of(), List.of(
        line(1, "brake pads", "PAD-100", bd("5"), "EA", 0.95, null))));

    assertThat(types(result)).contains(ValidationIssueType.MARGIN_BELOW_GUARDRAIL);
    assertThat(result.approvalRequirements()).anyMatch(a -> a.type() == ApprovalRequirementType.MARGIN_GUARDRAIL_APPROVAL);
    assertThat(result.riskDecision()).isEqualTo(ValidationRiskDecision.REQUIRES_MANAGER_APPROVAL);
  }

  @Test void crossTenantProductCannotBeMatched() {
    UUID otherTenant = UUID.randomUUID();
    String norm = ProductCodeNormalizer.normalize("PAD-100");
    // Product exists only for a different tenant; current-tenant lookup returns empty (Mockito default).
    Product otherTenantProduct = product(UUID.randomUUID(), "PAD-100", "Brake Pad", null, null);
    when(productRepository.findByTenantIdAndNormalizedSkuAndDeletedAtIsNullAndStatus(otherTenant, norm, "ACTIVE"))
        .thenReturn(List.of(otherTenantProduct));

    ExtractedRequestValidationResult result = service.validate(cmd("RFQ", null, "ACME", 0.9, List.of(), List.of(
        line(1, "brake pads", "PAD-100", bd("5"), "pcs", 0.9, null))));

    assertThat(result.lineResults().get(0).matchType()).isEqualTo("NONE");
    assertThat(types(result)).contains(ValidationIssueType.PRODUCT_NOT_FOUND);
  }

  @Test void happyPathRoutesAutoReadyDraft() {
    UUID custId = stubCustomerById();
    stubExactSku("PAD-100", "Brake Pad", bd("50"), null); // cost 50, price 100 -> 50% margin
    stubAvailableInventory("PAD-100");
    stubActivePrice("PAD-100", "100");
    when(marginRuleRepository.findByTenantIdAndActiveTrue(tenantId)).thenReturn(List.of());

    ExtractedRequestValidationResult result = service.validate(cmd("RFQ", custId, null, 0.95, List.of(), List.of(
        line(1, "brake pads", "PAD-100", bd("5"), "EA", 0.95, null))));

    assertThat(blocking(result)).isEmpty();
    assertThat(result.approvalRequirements()).isEmpty();
    assertThat(result.riskDecision()).isEqualTo(ValidationRiskDecision.AUTO_READY_DRAFT);
    assertThat(result.status()).isEqualTo(ValidationCaseStatus.VALIDATED);
  }

  @Test void validationNeverMutatesMasterDataOrCreatesQuoteOrder() {
    UUID custId = stubCustomerById();
    stubExactSku("PAD-100", "Brake Pad", bd("50"), null);
    stubAvailableInventory("PAD-100");
    stubActivePrice("PAD-100", "100");
    service.validate(cmd("RFQ", custId, null, 0.95, List.of(), List.of(
        line(1, "brake pads", "PAD-100", bd("5"), "EA", 0.95, null))));

    // Pure read engine: no save/delete on any reused repository, and no quote/order repository is even wired in.
    verify(productRepository, never()).save(any());
    verify(productAliasRepository, never()).save(any());
    verify(oemReferenceRepository, never()).save(any());
    verify(customerAccountRepository, never()).save(any());
    verify(inventorySnapshotRepository, never()).save(any());
    verify(priceRuleRepository, never()).save(any());
    verify(marginRuleRepository, never()).save(any());
    verify(productSubstituteRepository, never()).save(any());
    verify(productCompatibilityRepository, never()).save(any());
    verify(customerSubstitutionPreferenceRepository, never()).save(any());
  }

  // --- helpers ---

  private List<ValidationIssueView> blocking(ExtractedRequestValidationResult result) {
    return result.issues().stream()
        .filter(i -> i.severity() == ValidationSeverity.CRITICAL || i.severity() == ValidationSeverity.ERROR)
        .toList();
  }

  private List<ValidationIssueType> types(ExtractedRequestValidationResult result) {
    return result.issues().stream().map(ValidationIssueView::type).toList();
  }

  private UUID stubExactSku(String sku, String name, BigDecimal cost, String category) {
    UUID pid = UUID.randomUUID();
    String norm = ProductCodeNormalizer.normalize(sku);
    Product p = product(pid, sku, name, cost, category);
    when(productRepository.findByTenantIdAndNormalizedSkuAndDeletedAtIsNullAndStatus(tenantId, norm, "ACTIVE"))
        .thenReturn(List.of(p));
    // OP-CAP-09A: the engine reloads the matched product by id for margin/cost evaluation.
    lenient().when(productRepository.findByIdAndTenantIdAndDeletedAtIsNull(pid, tenantId)).thenReturn(Optional.of(p));
    return pid;
  }

  private void stubAvailableInventory(String sku) {
    // Look up the product id via the already-stubbed exact-sku call.
    UUID pid = productRepository.findByTenantIdAndNormalizedSkuAndDeletedAtIsNullAndStatus(
        tenantId, ProductCodeNormalizer.normalize(sku), "ACTIVE").get(0).getId();
    InventorySnapshot snap = mock(InventorySnapshot.class);
    lenient().when(snap.getQuantityAvailable()).thenReturn(new BigDecimal("999"));
    lenient().when(snap.getCapturedAt()).thenReturn(clock.instant());
    when(inventorySnapshotRepository.findTop50ByTenantIdAndProductIdOrderByCapturedAtDesc(tenantId, pid))
        .thenReturn(List.of(snap));
  }

  private void stubActivePrice(String sku, String unitPrice) {
    UUID pid = productRepository.findByTenantIdAndNormalizedSkuAndDeletedAtIsNullAndStatus(
        tenantId, ProductCodeNormalizer.normalize(sku), "ACTIVE").get(0).getId();
    PriceRule rule = mock(PriceRule.class);
    lenient().when(rule.isActive()).thenReturn(true);
    lenient().when(rule.getProductId()).thenReturn(pid);
    lenient().when(rule.getCustomerAccountId()).thenReturn(null);
    lenient().when(rule.getMinQuantity()).thenReturn(BigDecimal.ONE);
    lenient().when(rule.getUnitPrice()).thenReturn(new BigDecimal(unitPrice));
    lenient().when(rule.getActiveFrom()).thenReturn(Instant.parse("2020-01-01T00:00:00Z"));
    lenient().when(rule.getActiveTo()).thenReturn(null);
    when(priceRuleRepository.findByTenantIdAndProductIdOrderByPriorityAsc(tenantId, pid)).thenReturn(List.of(rule));
  }

  private UUID stubCustomerById() {
    UUID custId = UUID.randomUUID();
    CustomerAccount account = mock(CustomerAccount.class);
    lenient().when(account.getId()).thenReturn(custId);
    lenient().when(account.getAccountCode()).thenReturn("ACME");
    lenient().when(account.getDisplayName()).thenReturn("Acme Parts");
    when(customerAccountRepository.findByIdAndTenantIdAndDeletedAtIsNull(custId, tenantId)).thenReturn(Optional.of(account));
    return custId;
  }

  private Product product(UUID id, String sku, String name, BigDecimal cost, String category) {
    Product product = mock(Product.class);
    lenient().when(product.getId()).thenReturn(id);
    lenient().when(product.getSku()).thenReturn(sku);
    lenient().when(product.getName()).thenReturn(name);
    lenient().when(product.getCost()).thenReturn(cost);
    lenient().when(product.getCategory()).thenReturn(category);
    return product;
  }

  private ValidationLineInput line(int idx, String text, String skuOrOem, BigDecimal qty, String uom, Double conf, BigDecimal discount) {
    return new ValidationLineInput(idx, text, skuOrOem, qty, uom, conf, null, null, discount, null, null, null, null);
  }

  private ValidateExtractedRequestCommand cmd(String intent, UUID custId, String custHint, Double docConf, List<String> signals, List<ValidationLineInput> lines) {
    return new ValidateExtractedRequestCommand(
        "CHANNEL_MESSAGE", "src-1", intent, docConf, signals, custHint, null, custId, null, null, null, lines);
  }

  private BigDecimal bd(String v) { return new BigDecimal(v); }
}
