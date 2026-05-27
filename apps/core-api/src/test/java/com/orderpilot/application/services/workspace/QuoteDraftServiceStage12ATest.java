package com.orderpilot.application.services.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.Stage12ADtos.*;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.ProductCatalogMatchingService;
import com.orderpilot.application.services.ProductCodeNormalizer;
import com.orderpilot.application.services.ProductSubstitutionService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.inventory.InventorySnapshot;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.location.Location;
import com.orderpilot.domain.location.LocationRepository;
import com.orderpilot.domain.pricing.*;
import com.orderpilot.domain.product.*;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.domain.workspace.QuoteApprovalRequestRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    QuoteDraftService.class,
    CustomerResolutionService.class,
    ProductResolutionService.class,
    QuoteInventoryValidationService.class,
    PricingService.class,
    QuoteMarginValidationService.class,
    SubstitutionService.class,
    ApprovalPolicyService.class,
    ProductCatalogMatchingService.class,
    ProductSubstitutionService.class,
    AuditEventService.class,
    CoreConfiguration.class
})
class QuoteDraftServiceStage12ATest {
  private static final Instant NOW = Instant.parse("2026-05-20T00:00:00Z");

  @Autowired private QuoteDraftService service;
  @Autowired private CustomerAccountRepository customers;
  @Autowired private ProductRepository products;
  @Autowired private ProductAliasRepository aliases;
  @Autowired private OEMReferenceRepository oemReferences;
  @Autowired private ProductSubstituteRepository substitutes;
  @Autowired private ProductCompatibilityRepository compatibility;
  @Autowired private CustomerSubstitutionPreferenceRepository substitutionPreferences;
  @Autowired private InventorySnapshotRepository inventory;
  @Autowired private LocationRepository locations;
  @Autowired private PriceRuleRepository prices;
  @Autowired private DiscountRuleRepository discounts;
  @Autowired private MarginRuleRepository margins;
  @Autowired private DraftQuoteRepository quotes;
  @Autowired private QuoteApprovalRequestRepository approvalRequests;
  @Autowired private AuditEventRepository auditEvents;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void happyPathCreatesDraftQuoteWithoutApproval() {
    UUID tenantId = tenant();
    CustomerAccount customer = customer("CUST-1", "ACME", "Acme Parts");
    Product product = product("BRK-001", "Brake pads", "Brake", "60.00");
    price(product, customer, "100.00");
    inventory(product, "ALM", "10");
    discountRule(customer, product, "10.00", "5.00");
    marginRule(product, "20.00", "25.00");

    QuoteTransactionResponse response = service.createFromRfq(command(tenantId, "CUST-1", null, "BRK-001", "2", "0.00", "stage12-happy"));

    assertThat(response.status()).isEqualTo("DRAFT");
    assertThat(response.resolvedCustomer().id()).isEqualTo(customer.getId());
    assertThat(response.lines()).hasSize(1);
    assertThat(response.lines().get(0).productId()).isEqualTo(product.getId());
    assertThat(response.lines().get(0).lineTotal()).isEqualByComparingTo("200.00");
    assertThat(response.approvalRequired()).isFalse();
    assertThat(response.validationIssues()).isEmpty();
  }

  @Test
  void aliasMatchCreatesDraftQuoteWithResolvedInternalSku() {
    UUID tenantId = tenant();
    customer("CUST-1", "ACME", "Acme Parts");
    Product product = product("BRK-001", "Brake pads", "Brake", "60.00");
    aliases.save(new ProductAlias(tenantId, product.getId(), "CUSTOMER_SKU", "toyota brake", ProductCodeNormalizer.normalize("toyota brake"), null, new BigDecimal("0.95"), NOW));
    price(product, null, "100.00");
    inventory(product, "ALM", "10");
    marginRule(product, "20.00", "25.00");

    QuoteTransactionResponse response = service.createFromRfq(command(tenantId, "CUST-1", null, "toyota brake", "1", "0.00", null));

    assertThat(response.status()).isEqualTo("DRAFT");
    assertThat(response.lines().get(0).normalizedSku()).isEqualTo("TOYOTABRAKE");
    assertThat(response.lines().get(0).productId()).isEqualTo(product.getId());
  }

  @Test
  void outOfStockCreatesValidationIssueAndSubstituteCandidate() {
    UUID tenantId = tenant();
    CustomerAccount customer = customer("CUST-1", "ACME", "Acme Parts");
    Product original = product("OEM-BPAD", "OEM brake pads", "Brake", "60.00");
    Product safeSubstitute = product("AFT-BPAD", "Aftermarket brake pads", "Brake", "50.00");
    price(original, customer, "100.00");
    price(safeSubstitute, customer, "90.00");
    inventory(original, "ALM", "0");
    inventory(safeSubstitute, "ALM", "20");
    substitutes.save(new ProductSubstitute(tenantId, original.getId(), safeSubstitute.getId(), "COMPATIBLE_ALTERNATIVE", "LOW", false, "Safe deterministic substitute", NOW));
    compatibility.save(new ProductCompatibility(tenantId, safeSubstitute.getId(), "VEHICLE", "Toyota", "Camry", 2018, 2018, null, "Verified", "LOW", NOW));
    marginRule(original, "20.00", "25.00");

    QuoteTransactionResponse response = service.createFromRfq(command(tenantId, "CUST-1", null, "OEM-BPAD", "5", "0.00", null));

    assertThat(response.status()).isEqualTo("NEEDS_REVIEW");
    assertThat(response.validationIssues()).extracting(ValidationIssue::issueCode).contains("INSUFFICIENT_STOCK", "PRODUCT_OUT_OF_STOCK_SUBSTITUTE_AVAILABLE");
    assertThat(response.substituteCandidates()).extracting(SubstituteCandidate::productId).contains(safeSubstitute.getId());
  }

  @Test
  void marginViolationCreatesApprovalRequest() {
    UUID tenantId = tenant();
    CustomerAccount customer = customer("CUST-1", "ACME", "Acme Parts");
    Product product = product("LOW-MARGIN", "Low margin item", "Brake", "80.00");
    price(product, customer, "100.00");
    inventory(product, "ALM", "10");
    discountRule(customer, product, "30.00", "10.00");
    marginRule(product, "20.00", "25.00");

    QuoteTransactionResponse response = service.createFromRfq(command(tenantId, "CUST-1", null, "LOW-MARGIN", "1", "15.00", null));

    assertThat(response.approvalRequired()).isTrue();
    assertThat(response.approvalReasons()).contains("MARGIN_BELOW_GUARDRAIL");
    assertThat(response.validationIssues()).extracting(ValidationIssue::issueCode).contains("MARGIN_BELOW_GUARDRAIL", "DISCOUNT_APPROVAL_REQUIRED");
    assertThat(approvalRequests.countByTenantIdAndDraftQuoteIdAndStatus(tenantId, response.draftQuoteId(), "OPEN")).isGreaterThan(0);
  }

  @Test
  void unknownProductCreatesValidationIssueAndLineIsNotAutoApproved() {
    UUID tenantId = tenant();
    customer("CUST-1", "ACME", "Acme Parts");

    QuoteTransactionResponse response = service.createFromRfq(command(tenantId, "CUST-1", null, "UNKNOWN-SKU", "1", "0.00", null));

    assertThat(response.status()).isEqualTo("NEEDS_REVIEW");
    assertThat(response.lines().get(0).productId()).isNull();
    assertThat(response.validationIssues()).extracting(ValidationIssue::issueCode).contains("PRODUCT_NOT_RESOLVED");
  }

  @Test
  void blockedSubstituteIsReturnedAsBlockedAndRequiresApproval() {
    UUID tenantId = tenant();
    CustomerAccount customer = customer("CUST-1", "ACME", "Acme Parts");
    Product original = product("OEM-BPAD", "OEM brake pads", "Brake", "60.00");
    Product blocked = product("BLOCKED-BPAD", "Blocked brake pads", "Brake", "50.00");
    price(original, customer, "100.00");
    inventory(original, "ALM", "0");
    inventory(blocked, "ALM", "20");
    substitutes.save(new ProductSubstitute(tenantId, original.getId(), blocked.getId(), "COMPATIBLE_ALTERNATIVE", "HIGH", true, "Risky substitute", NOW));
    substitutionPreferences.save(new CustomerSubstitutionPreference(tenantId, customer.getId(), original.getId(), null, false, null, blocked.getId(), "Blocked by customer", NOW));
    marginRule(original, "20.00", "25.00");

    QuoteTransactionResponse response = service.createFromRfq(command(tenantId, "CUST-1", null, "OEM-BPAD", "5", "0.00", null));

    assertThat(response.substituteCandidates()).anySatisfy(candidate -> {
      assertThat(candidate.productId()).isEqualTo(blocked.getId());
      assertThat(candidate.blocked()).isTrue();
      assertThat(candidate.requiresApproval()).isTrue();
    });
    assertThat(response.approvalReasons()).contains("SUBSTITUTE_BLOCKED_FOR_CUSTOMER");
  }

  @Test
  void tenantIsolationPreventsUsingTenantBData() {
    UUID tenantB = tenant();
    product("B-SKU", "Tenant B product", "Brake", "10.00");
    customer("B-CUST", "B", "Tenant B");

    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    QuoteTransactionResponse response = service.createFromRfq(command(tenantA, "B-CUST", null, "B-SKU", "1", "0.00", null));

    assertThat(response.resolvedCustomer()).isNull();
    assertThat(response.lines().get(0).productId()).isNull();
    assertThat(response.validationIssues()).extracting(ValidationIssue::issueCode).contains("CUSTOMER_NOT_RESOLVED", "PRODUCT_NOT_RESOLVED");
  }

  @Test
  void draftQuoteCreationEmitsAuditEvents() {
    UUID tenantId = tenant();
    customer("CUST-1", "ACME", "Acme Parts");

    QuoteTransactionResponse response = service.createFromRfq(command(tenantId, "CUST-1", null, "UNKNOWN", "1", "0.00", null));

    assertThat(response.auditCorrelationId()).isNotNull();
    assertThat(auditEvents.findAll()).extracting("action").contains("DRAFT_QUOTE_RFQ_CREATE_REQUESTED", "DRAFT_QUOTE_CREATED");
  }

  @Test
  void duplicateIdempotencyKeyDoesNotCreateDuplicateQuote() {
    UUID tenantId = tenant();
    customer("CUST-1", "ACME", "Acme Parts");

    QuoteTransactionResponse first = service.createFromRfq(command(tenantId, "CUST-1", null, "UNKNOWN", "1", "0.00", "idem-1"));
    QuoteTransactionResponse second = service.createFromRfq(command(tenantId, "CUST-1", null, "UNKNOWN", "1", "0.00", "idem-1"));

    assertThat(second.draftQuoteId()).isEqualTo(first.draftQuoteId());
    assertThat(quotes.findByTenantIdOrderByCreatedAtDesc(tenantId)).hasSize(1);
  }

  private UUID tenant() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    locations.save(new Location(tenantId, "ALM", "Almaty", "WAREHOUSE", null, "Almaty", "KZ", true, NOW));
    return tenantId;
  }

  private CustomerAccount customer(String externalRef, String code, String name) {
    return customers.save(new CustomerAccount(TenantContext.requireTenantId(), externalRef, code, name, name, null, "ACTIVE", "USD", null, NOW));
  }

  private Product product(String sku, String name, String category, String cost) {
    return products.save(new Product(TenantContext.requireTenantId(), sku, name, null, category, null, null, "EA", "ACTIVE", new BigDecimal(cost), "USD", NOW));
  }

  private void price(Product product, CustomerAccount customer, String unitPrice) {
    prices.save(new PriceRule(TenantContext.requireTenantId(), product.getId(), customer == null ? null : customer.getId(), null, null, BigDecimal.ONE, "EA", new BigDecimal(unitPrice), "USD", NOW, null, 10, NOW));
  }

  private void inventory(Product product, String locationCode, String available) {
    UUID tenantId = TenantContext.requireTenantId();
    UUID locationId = locations.findByTenantIdAndCode(tenantId, locationCode).orElseThrow().getId();
    inventory.save(new InventorySnapshot(tenantId, product.getId(), locationId, new BigDecimal(available), new BigDecimal(available), BigDecimal.ZERO, NOW, "TEST", null, NOW));
  }

  private void discountRule(CustomerAccount customer, Product product, String max, String approvalAbove) {
    discounts.save(new DiscountRule(TenantContext.requireTenantId(), "DISC-" + product.getSku(), "Discount " + product.getSku(), customer.getId(), null, product.getId(), new BigDecimal(max), new BigDecimal(approvalAbove), NOW, null, NOW));
  }

  private void marginRule(Product product, String min, String approvalBelow) {
    margins.save(new MarginRule(TenantContext.requireTenantId(), "MARGIN-" + product.getSku(), "Margin " + product.getSku(), product.getId(), null, null, new BigDecimal(min), new BigDecimal(approvalBelow), NOW));
  }

  private CreateDraftQuoteFromRfqCommand command(UUID tenantId, String customerExternalRef, String customerName, String sku, String quantity, String discount, String idempotencyKey) {
    return new CreateDraftQuoteFromRfqCommand(tenantId, UUID.randomUUID(), "OPERATOR", customerExternalRef, customerName, List.of(new RequestedItem(sku, "Requested " + sku, new BigDecimal(quantity), "EA")), "ALM", new BigDecimal(discount), idempotencyKey);
  }
}
