package com.orderpilot.application.services.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.Stage11ADtos.*;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.ProductCatalogMatchingService;
import com.orderpilot.application.services.ProductCodeNormalizer;
import com.orderpilot.application.services.ProductSubstitutionService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.common.tenant.TenantContextMissingException;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.integration.CompensationPlanRepository;
import com.orderpilot.domain.integration.ConnectorCommandRepository;
import com.orderpilot.domain.integration.ConnectorSandboxExecutionRepository;
import com.orderpilot.domain.intake.ChannelMessage;
import com.orderpilot.domain.intake.ChannelMessageRepository;
import com.orderpilot.domain.inventory.InventorySnapshot;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.pricing.MarginRuleRepository;
import com.orderpilot.domain.pricing.PriceRule;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductAlias;
import com.orderpilot.domain.product.ProductAliasRepository;
import com.orderpilot.domain.product.ProductCompatibility;
import com.orderpilot.domain.product.ProductCompatibilityRepository;
import com.orderpilot.domain.product.OEMReference;
import com.orderpilot.domain.product.OEMReferenceRepository;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.product.ProductSubstitute;
import com.orderpilot.domain.product.ProductSubstituteRepository;
import com.orderpilot.domain.workspace.*;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.policy.TenantPolicyException;
import com.orderpilot.security.policy.TenantPolicyService;
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
@Import({RfqToDraftQuoteService.class, ProductCatalogMatchingService.class, ProductSubstitutionService.class, TenantPolicyService.class, AuditEventService.class, CoreConfiguration.class})
class RfqToDraftQuoteServiceTest {
  @Autowired private RfqToDraftQuoteService service;
  @Autowired private CustomerAccountRepository customerRepository;
  @Autowired private ProductRepository productRepository;
  @Autowired private ProductAliasRepository aliasRepository;
  @Autowired private OEMReferenceRepository oemRepository;
  @Autowired private ProductSubstituteRepository substituteRepository;
  @Autowired private ProductCompatibilityRepository compatibilityRepository;
  @Autowired private PriceRuleRepository priceRuleRepository;
  @Autowired private InventorySnapshotRepository inventoryRepository;
  @Autowired private DraftQuoteRepository quoteRepository;
  @Autowired private DraftQuoteLineRepository lineRepository;
  @Autowired private QuoteValidationIssueRepository issueRepository;
  @Autowired private ChannelMessageRepository channelMessageRepository;
  @Autowired private ConnectorCommandRepository connectorCommandRepository;
  @Autowired private ConnectorSandboxExecutionRepository sandboxExecutionRepository;
  @Autowired private CompensationPlanRepository compensationPlanRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private MarginRuleRepository marginRuleRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void operatorCanCreateReadyInternalDraftQuoteFromStructuredRfq() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID actorId = UUID.randomUUID();
    CustomerAccount customer = customer();
    Product product = product("BRK-001", "Brake pads");
    price(product.getId(), new BigDecimal("100.00"));
    inventory(product.getId(), new BigDecimal("50"));

    DraftQuoteResponse response = service.createFromRfq(command(actorId, "OPERATOR", "ACME", List.of(new RfqLineInput("Front brake pads", "BRK-001", new BigDecimal("2"), "pcs", "Almaty"))));

    assertThat(response.tenantId()).isEqualTo(tenantId);
    assertThat(response.customerAccountId()).isEqualTo(customer.getId());
    assertThat(response.status()).isEqualTo("READY_FOR_APPROVAL");
    assertThat(response.validationStatus()).isEqualTo("VALIDATED");
    assertThat(response.lines()).hasSize(1);
    assertThat(response.lines().get(0).productId()).isEqualTo(product.getId());
    assertThat(response.lines().get(0).uom()).isEqualTo("EA");
    assertThat(response.lines().get(0).lineTotal()).isEqualByComparingTo("200.00");
    assertThat(response.issues()).extracting("issueCode").contains("MARGIN_NOT_EVALUATED");
    assertThat(connectorCommandRepository.count()).isZero();
    assertThat(sandboxExecutionRepository.count()).isZero();
    assertThat(compensationPlanRepository.count()).isZero();
    assertThat(auditEventRepository.findAll()).extracting("action").contains("DRAFT_QUOTE_CREATION_REQUESTED", "DRAFT_QUOTE_CREATED", "DRAFT_QUOTE_VALIDATION_COMPLETED");
    assertThat(marginRuleRepository.count()).isZero();
  }

  @Test
  void salesQuoteManagerCanCreateDraftQuoteUsingProductAliasAndCyrillicUom() {
    TenantContext.setTenantId(UUID.randomUUID());
    UUID actorId = UUID.randomUUID();
    customer();
    Product product = product("BRK-001", "Brake pads");
    aliasRepository.save(new ProductAlias(TenantContext.requireTenantId(), product.getId(), "OEM", "toyota-brake", ProductCodeNormalizer.normalize("toyota-brake"), null, new BigDecimal("0.98"), Instant.parse("2026-05-20T00:00:00Z")));
    price(product.getId(), new BigDecimal("75.00"));
    inventory(product.getId(), new BigDecimal("20"));

    DraftQuoteResponse response = service.createFromRfq(command(actorId, "SALES_QUOTE_MANAGER", "ACME", List.of(new RfqLineInput("Toyota brake pads", "toyota-brake", new BigDecimal("3"), "шт", null))));

    assertThat(response.status()).isEqualTo("READY_FOR_APPROVAL");
    assertThat(response.lines().get(0).normalizedSku()).isEqualTo("TOYOTABRAKE");
    assertThat(response.lines().get(0).uom()).isEqualTo("EA");
  }

  @Test
  void rfqLineResolvesProductThroughOemReference() {
    TenantContext.setTenantId(UUID.randomUUID());
    UUID actorId = UUID.randomUUID();
    customer();
    Product product = product("FILTER-17801-0H050", "Toyota Air Filter 17801-0H050");
    oemRepository.save(new OEMReference(TenantContext.requireTenantId(), product.getId(), "17801-0H050", ProductCodeNormalizer.normalize("17801-0H050"), "Toyota", Instant.parse("2026-05-20T00:00:00Z")));
    price(product.getId(), new BigDecimal("35.00"));
    inventory(product.getId(), new BigDecimal("10"));

    DraftQuoteResponse response = service.createFromRfq(command(actorId, "OPERATOR", "ACME", List.of(new RfqLineInput("Toyota filter", "17801 / 0h050", new BigDecimal("2"), "pcs", null))));

    assertThat(response.status()).isEqualTo("READY_FOR_APPROVAL");
    assertThat(response.lines().get(0).productId()).isEqualTo(product.getId());
    assertThat(response.lines().get(0).normalizedSku()).isEqualTo("178010H050");
    assertThat(response.lines().get(0).productName()).isEqualTo("Toyota Air Filter 17801-0H050");
    assertThat(response.lines().get(0).confidenceScore()).isEqualByComparingTo("0.90");
  }

  @Test
  void ambiguousProductMatchCreatesReviewIssueWithoutExternalSideEffects() {
    TenantContext.setTenantId(UUID.randomUUID());
    customer();
    Product first = product("BRK-CAMRY-2018-OEM", "Toyota Camry 2018 OEM Brake Pads");
    Product second = product("BRK-CAMRY-2018-AFT-A", "Toyota Camry 2018 Aftermarket Brake Pads A");
    aliasRepository.save(new ProductAlias(TenantContext.requireTenantId(), first.getId(), "CUSTOMER_SKU", "AB-1209", ProductCodeNormalizer.normalize("AB-1209"), null, new BigDecimal("0.95"), Instant.parse("2026-05-20T00:00:00Z")));
    aliasRepository.save(new ProductAlias(TenantContext.requireTenantId(), second.getId(), "CUSTOMER_SKU", "AB1209", ProductCodeNormalizer.normalize("AB1209"), null, new BigDecimal("0.95"), Instant.parse("2026-05-20T00:00:00Z")));

    DraftQuoteResponse response = service.createFromRfq(command(UUID.randomUUID(), "OPERATOR", "ACME", List.of(new RfqLineInput("Brake pads", "AB-1209", BigDecimal.ONE, "pcs", null))));

    assertThat(response.status()).isEqualTo("NEEDS_REVIEW");
    assertThat(response.lines().get(0).productId()).isNull();
    assertThat(response.issues()).extracting("issueCode").contains("PRODUCT_MATCH_AMBIGUOUS");
    assertThat(connectorCommandRepository.count()).isZero();
    assertThat(sandboxExecutionRepository.count()).isZero();
    assertThat(compensationPlanRepository.count()).isZero();
  }

  @Test
  void tenantACannotResolveTenantBProductAliasOrOemIntoDraftQuoteLine() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantB);
    Product tenantBProduct = product("B-ONLY", "Tenant B Product");
    aliasRepository.save(new ProductAlias(tenantB, tenantBProduct.getId(), "CUSTOMER_SKU", "AB-1209", ProductCodeNormalizer.normalize("AB-1209"), null, new BigDecimal("0.95"), Instant.parse("2026-05-20T00:00:00Z")));
    oemRepository.save(new OEMReference(tenantB, tenantBProduct.getId(), "17801-0H050", ProductCodeNormalizer.normalize("17801-0H050"), "Toyota", Instant.parse("2026-05-20T00:00:00Z")));

    TenantContext.setTenantId(tenantA);
    DraftQuoteResponse aliasResponse = service.createFromRfq(command(UUID.randomUUID(), "OPERATOR", null, List.of(new RfqLineInput("Brake pads", "AB-1209", BigDecimal.ONE, "pcs", null))));
    DraftQuoteResponse oemResponse = service.createFromRfq(command(UUID.randomUUID(), "OPERATOR", null, List.of(new RfqLineInput("Filter", "17801-0H050", BigDecimal.ONE, "pcs", null))));

    assertThat(aliasResponse.lines().get(0).productId()).isNull();
    assertThat(oemResponse.lines().get(0).productId()).isNull();
    assertThat(aliasResponse.issues()).extracting("issueCode").contains("PRODUCT_NOT_RESOLVED");
    assertThat(oemResponse.issues()).extracting("issueCode").contains("PRODUCT_NOT_RESOLVED");
  }

  @Test
  void readOnlyViewerAndAuditorCannotCreateDraftQuote() {
    TenantContext.setTenantId(UUID.randomUUID());

    assertThatThrownBy(() -> service.createFromRfq(command(UUID.randomUUID(), "READ_ONLY_VIEWER", "ACME", List.of(new RfqLineInput("x", "x", BigDecimal.ONE, "pcs", null)))))
        .isInstanceOf(TenantPolicyException.class);
    assertThatThrownBy(() -> service.createFromRfq(command(UUID.randomUUID(), "AUDITOR", "ACME", List.of(new RfqLineInput("x", "x", BigDecimal.ONE, "pcs", null)))))
        .isInstanceOf(TenantPolicyException.class);
    assertThat(quoteRepository.count()).isZero();
    assertThat(auditEventRepository.findAll()).extracting("action").contains("DRAFT_QUOTE_CREATION_DENIED_BY_POLICY");
  }

  @Test
  void missingTenantContextDeniesDraftQuoteCreation() {
    assertThatThrownBy(() -> service.createFromRfq(command(UUID.randomUUID(), "OPERATOR", "ACME", List.of(new RfqLineInput("x", "x", BigDecimal.ONE, "pcs", null)))))
        .isInstanceOf(TenantContextMissingException.class);
  }

  @Test
  void tenantMismatchOnSourceMessageDeniesBeforeQuoteCreation() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    ChannelMessage message = channelMessageRepository.save(new ChannelMessage(tenantB, "TELEGRAM", "msg-b", "chat-b", "buyer", "Buyer", null, "INBOUND", "TEXT", "Need quote", "{}", "RECEIVED", Instant.parse("2026-05-20T00:00:00Z")));
    TenantContext.setTenantId(tenantA);

    assertThatThrownBy(() -> service.createFromRfq(new CreateDraftQuoteFromRfqRequest(UUID.randomUUID(), "OPERATOR", "CHANNEL_MESSAGE", message.getId(), null, "ACME", null, List.of(new RfqLineInput("x", "x", BigDecimal.ONE, "pcs", null)))))
        .isInstanceOf(NotFoundException.class);
    assertThat(quoteRepository.count()).isZero();
  }

  @Test
  void unresolvedCustomerProductPriceAndInvalidQuantityCreateReviewIssues() {
    TenantContext.setTenantId(UUID.randomUUID());

    DraftQuoteResponse response = service.createFromRfq(command(UUID.randomUUID(), "OPERATOR", "Unknown Customer", List.of(new RfqLineInput("Mystery part", "NOPE", BigDecimal.ZERO, "box", null))));

    assertThat(response.status()).isEqualTo("NEEDS_REVIEW");
    assertThat(response.requiresHumanReview()).isTrue();
    assertThat(response.issues()).extracting("issueCode")
        .contains("CUSTOMER_NOT_RESOLVED", "PRODUCT_NOT_RESOLVED", "INVALID_QUANTITY", "UOM_UNRECOGNIZED");
    assertThat(issueRepository.countByTenantIdAndDraftQuoteIdAndBlockingTrue(TenantContext.requireTenantId(), response.id())).isGreaterThan(0);
  }

  @Test
  void missingPriceAndInsufficientStockCreateBlockingIssues() {
    TenantContext.setTenantId(UUID.randomUUID());
    customer();
    Product product = product("FLT-001", "Filter");
    inventory(product.getId(), new BigDecimal("1"));

    DraftQuoteResponse response = service.createFromRfq(command(UUID.randomUUID(), "OPERATOR", "ACME", List.of(new RfqLineInput("Filter", "FLT-001", new BigDecimal("5"), "units", null))));

    assertThat(response.status()).isEqualTo("NEEDS_REVIEW");
    assertThat(response.issues()).extracting("issueCode").contains("PRICE_NOT_RESOLVED", "INSUFFICIENT_STOCK");
    assertThat(response.lines().get(0).availableStock()).isEqualByComparingTo("1");
  }

  @Test
  void outOfStockOriginalExposesSubstituteCandidatesWithoutSelectingThem() {
    TenantContext.setTenantId(UUID.randomUUID());
    customer();
    Product original = product("TOY-CAM-2018-BPAD-OE", "Original brake pads for Toyota Camry 2018");
    Product substitute = product("AFT-CAM-2018-BPAD-A", "Aftermarket compatible substitute A");
    price(original.getId(), new BigDecimal("260.00"));
    price(substitute.getId(), new BigDecimal("190.00"));
    inventory(original.getId(), BigDecimal.ZERO);
    inventory(substitute.getId(), new BigDecimal("75"));
    substituteRepository.save(new ProductSubstitute(TenantContext.requireTenantId(), original.getId(), substitute.getId(), "COMPATIBLE_ALTERNATIVE", "LOW", false, "Verified aftermarket substitute", Instant.parse("2026-05-20T00:00:00Z")));
    compatibilityRepository.save(new ProductCompatibility(TenantContext.requireTenantId(), substitute.getId(), "VEHICLE", "Toyota", "Camry", 2018, 2018, null, "Verified Camry 2018 fitment", "LOW", Instant.parse("2026-05-20T00:00:00Z")));

    DraftQuoteResponse response = service.createFromRfq(command(UUID.randomUUID(), "OPERATOR", "ACME", List.of(new RfqLineInput("Need brake pads for Toyota Camry 2018", "TOY-CAM-2018-BPAD-OE", new BigDecimal("20"), "pcs", null))));

    assertThat(response.status()).isEqualTo("SUBSTITUTION_REVIEW");
    assertThat(response.lines().get(0).productId()).isEqualTo(original.getId());
    assertThat(response.lines().get(0).substitutionCandidates()).hasSize(1);
    assertThat(response.lines().get(0).substitutionCandidates().get(0).productId()).isEqualTo(substitute.getId());
    assertThat(response.lines().get(0).substitutionCandidates().get(0).stockStatus()).isEqualTo("AVAILABLE");
    assertThat(response.issues()).extracting("issueCode").contains("PRODUCT_OUT_OF_STOCK_SUBSTITUTE_AVAILABLE");
    assertThat(connectorCommandRepository.count()).isZero();
    assertThat(sandboxExecutionRepository.count()).isZero();
    assertThat(compensationPlanRepository.count()).isZero();
  }

  @Test
  void deterministicParserHandlesDemoTelegramTextWithoutAiProvider() {
    TenantContext.setTenantId(UUID.randomUUID());

    DraftQuoteResponse response = service.createFromRfq(new CreateDraftQuoteFromRfqRequest(UUID.randomUUID(), "OPERATOR", "TELEGRAM_MESSAGE", null, null, null, "Need brake pads for Toyota Camry 2018, 20 pcs, wholesale, Almaty", List.of()));

    assertThat(response.status()).isEqualTo("NEEDS_REVIEW");
    assertThat(response.lines()).hasSize(1);
    assertThat(response.lines().get(0).quantity()).isEqualByComparingTo("20");
    assertThat(response.lines().get(0).uom()).isEqualTo("EA");
    assertThat(response.lines().get(0).rawText()).contains("Toyota Camry 2018");
    assertThat(response.issues()).extracting("issueCode").contains("CUSTOMER_NOT_RESOLVED", "PRODUCT_NOT_RESOLVED");
  }

  @Test
  void tenantACannotReadTenantBDraftQuote() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantB);
    DraftQuoteResponse bQuote = service.createFromRfq(command(UUID.randomUUID(), "OPERATOR", "B", List.of(new RfqLineInput("x", "x", BigDecimal.ONE, "pcs", null))));

    TenantContext.setTenantId(tenantA);

    assertThatThrownBy(() -> service.get(bQuote.id())).isInstanceOf(NotFoundException.class);
  }

  private CreateDraftQuoteFromRfqRequest command(UUID actorId, String role, String customerHint, List<RfqLineInput> lines) {
    return new CreateDraftQuoteFromRfqRequest(actorId, role, "API", null, null, customerHint, null, lines);
  }

  private CustomerAccount customer() {
    return customerRepository.save(new CustomerAccount(TenantContext.requireTenantId(), null, "ACME", "Acme LLP", "Acme", null, "ACTIVE", "USD", null, Instant.parse("2026-05-20T00:00:00Z")));
  }

  private Product product(String sku, String name) {
    return productRepository.save(new Product(TenantContext.requireTenantId(), sku, name, null, "PARTS", null, null, "EA", "ACTIVE", null, "USD", Instant.parse("2026-05-20T00:00:00Z")));
  }

  private PriceRule price(UUID productId, BigDecimal unitPrice) {
    return priceRuleRepository.save(new PriceRule(TenantContext.requireTenantId(), productId, null, null, null, BigDecimal.ONE, "EA", unitPrice, "USD", Instant.parse("2026-01-01T00:00:00Z"), null, 10, Instant.parse("2026-05-20T00:00:00Z")));
  }

  private InventorySnapshot inventory(UUID productId, BigDecimal available) {
    return inventoryRepository.save(new InventorySnapshot(TenantContext.requireTenantId(), productId, UUID.randomUUID(), available, available, BigDecimal.ZERO, Instant.parse("2026-05-20T00:00:00Z"), "TEST", null, Instant.parse("2026-05-20T00:00:00Z")));
  }
}
