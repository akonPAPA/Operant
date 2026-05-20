package com.orderpilot.demo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.application.services.ProductCodeNormalizer;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.location.Location;
import com.orderpilot.domain.location.LocationRepository;
import com.orderpilot.domain.inventory.InventorySnapshot;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.pricing.PriceRule;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.product.CustomerSubstitutionPreference;
import com.orderpilot.domain.product.CustomerSubstitutionPreferenceRepository;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductAlias;
import com.orderpilot.domain.product.ProductAliasRepository;
import com.orderpilot.domain.product.ProductCompatibility;
import com.orderpilot.domain.product.ProductCompatibilityRepository;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.product.ProductSubstitute;
import com.orderpilot.domain.product.ProductSubstituteRepository;
import com.orderpilot.domain.reconciliation.InventoryMovement;
import com.orderpilot.domain.reconciliation.InventoryMovementRepository;
import com.orderpilot.domain.reconciliation.InventoryMovementType;
import com.orderpilot.domain.tenant.Tenant;
import com.orderpilot.domain.tenant.TenantRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoDataService {
  private static final String BASE = "demo/core-v1-demo/";
  private final ObjectMapper objectMapper;
  private final TenantRepository tenantRepository;
  private final CustomerAccountRepository customerRepository;
  private final ProductRepository productRepository;
  private final ProductAliasRepository aliasRepository;
  private final ProductSubstituteRepository substituteRepository;
  private final ProductCompatibilityRepository compatibilityRepository;
  private final CustomerSubstitutionPreferenceRepository substitutionPreferenceRepository;
  private final LocationRepository locationRepository;
  private final InventoryMovementRepository movementRepository;
  private final InventorySnapshotRepository inventorySnapshotRepository;
  private final PriceRuleRepository priceRuleRepository;
  private final Clock clock;

  public DemoDataService(ObjectMapper objectMapper, TenantRepository tenantRepository, CustomerAccountRepository customerRepository, ProductRepository productRepository, ProductAliasRepository aliasRepository, ProductSubstituteRepository substituteRepository, ProductCompatibilityRepository compatibilityRepository, CustomerSubstitutionPreferenceRepository substitutionPreferenceRepository, LocationRepository locationRepository, InventoryMovementRepository movementRepository, InventorySnapshotRepository inventorySnapshotRepository, PriceRuleRepository priceRuleRepository, Clock clock) {
    this.objectMapper = objectMapper;
    this.tenantRepository = tenantRepository;
    this.customerRepository = customerRepository;
    this.productRepository = productRepository;
    this.aliasRepository = aliasRepository;
    this.substituteRepository = substituteRepository;
    this.compatibilityRepository = compatibilityRepository;
    this.substitutionPreferenceRepository = substitutionPreferenceRepository;
    this.locationRepository = locationRepository;
    this.movementRepository = movementRepository;
    this.inventorySnapshotRepository = inventorySnapshotRepository;
    this.priceRuleRepository = priceRuleRepository;
    this.clock = clock;
  }

  @Transactional
  public DemoSeedResult seedCoreV1Demo() {
    TenantFixture tenantFixture = read("tenant-demo.json", TenantFixture.class);
    Tenant tenant = tenantRepository.findBySlug(tenantFixture.slug())
        .orElseGet(() -> tenantRepository.save(new Tenant(tenantFixture.slug(), tenantFixture.legalName(), tenantFixture.status(), clock.instant())));
    ProductsFixture productsFixture = read("products-demo.json", ProductsFixture.class);
    Location location = locationRepository.findByTenantIdAndCode(tenant.getId(), productsFixture.location().code())
        .orElseGet(() -> locationRepository.save(new Location(tenant.getId(), productsFixture.location().code(), productsFixture.location().name(), productsFixture.location().type(), null, productsFixture.location().city(), productsFixture.location().country(), true, clock.instant())));
    List<CustomerFixture> customers = readList("customers-demo.json", new TypeReference<>() {});
    CustomerAccount customer = customerRepository.findByTenantIdAndAccountCodeAndDeletedAtIsNull(tenant.getId(), customers.getFirst().accountCode())
        .orElseGet(() -> customerRepository.save(new CustomerAccount(tenant.getId(), customers.getFirst().externalRef(), customers.getFirst().accountCode(), customers.getFirst().legalName(), customers.getFirst().displayName(), null, customers.getFirst().status(), customers.getFirst().defaultCurrency(), location.getId(), clock.instant())));
    Product primaryProduct = null;
    java.util.Map<String, Product> productsBySku = new java.util.HashMap<>();
    for (ProductFixture productFixture : productsFixture.products()) {
      Product product = productRepository.findByTenantIdAndSkuAndDeletedAtIsNull(tenant.getId(), productFixture.sku())
          .orElseGet(() -> productRepository.save(new Product(tenant.getId(), productFixture.sku(), productFixture.name(), productFixture.description(), productFixture.category(), productFixture.brand(), productFixture.manufacturer(), productFixture.baseUom(), productFixture.status(), productFixture.cost(), productFixture.currency(), clock.instant())));
      if (primaryProduct == null) primaryProduct = product;
      productsBySku.put(product.getSku(), product);
      if (productFixture.aliases() != null) {
        for (AliasFixture alias : productFixture.aliases()) {
          String normalizedAlias = ProductCodeNormalizer.normalize(alias.rawAlias());
          boolean exists = !aliasRepository.findByTenantIdAndNormalizedAliasAndActiveTrue(tenant.getId(), normalizedAlias).isEmpty();
          if (!exists) {
            aliasRepository.save(new ProductAlias(tenant.getId(), product.getId(), alias.aliasType(), alias.rawAlias(), normalizedAlias, customer.getId(), alias.confidenceDefault(), clock.instant()));
          }
        }
      }
    }
    seedStage11C(tenant.getId(), customer.getId(), location.getId(), productsBySku);
    List<MovementFixture> movements = readList("inventory-movements-demo.json", new TypeReference<>() {});
    for (MovementFixture movement : movements) {
      Product product = productRepository.findByTenantIdAndSkuAndDeletedAtIsNull(tenant.getId(), movement.productSku()).orElseThrow();
      Location movementLocation = locationRepository.findByTenantIdAndCode(tenant.getId(), movement.locationCode()).orElseThrow();
      if (!movementRepository.existsByTenantIdAndSourceTypeAndSourceReference(tenant.getId(), movement.sourceType(), movement.sourceReference())) {
        movementRepository.save(new InventoryMovement(tenant.getId(), product.getId(), movementLocation.getId(), InventoryMovementType.valueOf(movement.movementType()), movement.quantity(), Instant.parse(movement.occurredAt()), movement.sourceType(), movement.sourceReference(), clock.instant()));
      }
    }
    return new DemoSeedResult(tenant.getId(), customer.getId(), primaryProduct.getId(), location.getId(), tenant.getSlug(), primaryProduct.getSku(), location.getCode());
  }

  private void seedStage11C(UUID tenantId, UUID customerId, UUID locationId, java.util.Map<String, Product> productsBySku) {
    Product original = productsBySku.get("TOY-CAM-2018-BPAD-OE");
    Product substituteA = productsBySku.get("AFT-CAM-2018-BPAD-A");
    Product substituteB = productsBySku.get("AFT-CAM-2018-BPAD-B");
    if (original == null || substituteA == null || substituteB == null) {
      return;
    }

    saveSubstitute(tenantId, original.getId(), substituteA.getId(), "COMPATIBLE_ALTERNATIVE", "LOW", false, "RoadMax aftermarket pad accepted for Camry 2018 demo");
    saveSubstitute(tenantId, original.getId(), substituteB.getId(), "OEM_EQUIVALENT", "MEDIUM", true, "Budget substitute requires operator approval");
    saveCompatibility(tenantId, original.getId(), "Toyota", "Camry", 2018, 2018, "LOW", "Original Camry 2018 brake pad context");
    saveCompatibility(tenantId, substituteA.getId(), "Toyota", "Camry", 2018, 2018, "LOW", "Aftermarket A verified for Camry 2018");
    saveCompatibility(tenantId, substituteB.getId(), "Toyota", "Camry", 2018, 2018, "MEDIUM", "Aftermarket B compatible but needs approval");
    savePreference(tenantId, customerId, original.getId(), true, null, "Customer has accepted aftermarket Camry brake pads in demo history");
    savePreference(tenantId, customerId, original.getId(), false, substituteB.getId(), "Customer blocks SteppeLine budget brake pads");
    saveSnapshot(tenantId, original.getId(), locationId, "0", "0", "DEMO-STAGE11C-ORIGINAL-UNAVAILABLE");
    saveSnapshot(tenantId, substituteA.getId(), locationId, "80", "75", "DEMO-STAGE11C-SUB-A");
    saveSnapshot(tenantId, substituteB.getId(), locationId, "4", "4", "DEMO-STAGE11C-SUB-B-LOW");
    savePrice(tenantId, original.getId(), "26000.00");
    savePrice(tenantId, substituteA.getId(), "19000.00");
    savePrice(tenantId, substituteB.getId(), "15500.00");
  }

  private void saveSubstitute(UUID tenantId, UUID sourceProductId, UUID substituteProductId, String type, String risk, boolean requiresApproval, String notes) {
    if (!substituteRepository.existsByTenantIdAndSourceProductIdAndSubstituteProductIdAndSubstituteTypeAndActiveTrue(tenantId, sourceProductId, substituteProductId, type)) {
      substituteRepository.save(new ProductSubstitute(tenantId, sourceProductId, substituteProductId, type, risk, requiresApproval, notes, clock.instant()));
    }
  }

  private void saveCompatibility(UUID tenantId, UUID productId, String make, String model, Integer yearFrom, Integer yearTo, String risk, String notes) {
    if (!compatibilityRepository.existsByTenantIdAndProductIdAndMakeAndModelAndYearFromAndYearToAndActiveTrue(tenantId, productId, make, model, yearFrom, yearTo)) {
      compatibilityRepository.save(new ProductCompatibility(tenantId, productId, "VEHICLE", make, model, yearFrom, yearTo, null, notes, risk, clock.instant()));
    }
  }

  private void savePreference(UUID tenantId, UUID customerId, UUID productId, boolean allowAftermarket, UUID blockedSubstituteProductId, String notes) {
    if (blockedSubstituteProductId != null) {
      if (!substitutionPreferenceRepository.existsByTenantIdAndCustomerAccountIdAndProductIdAndBlockedSubstituteProductId(tenantId, customerId, productId, blockedSubstituteProductId)) {
        substitutionPreferenceRepository.save(new CustomerSubstitutionPreference(tenantId, customerId, productId, null, allowAftermarket, null, blockedSubstituteProductId, notes, clock.instant()));
      }
      return;
    }
    if (!substitutionPreferenceRepository.existsByTenantIdAndCustomerAccountIdAndProductIdAndAllowAftermarketTrue(tenantId, customerId, productId)) {
      substitutionPreferenceRepository.save(new CustomerSubstitutionPreference(tenantId, customerId, productId, null, allowAftermarket, null, null, notes, clock.instant()));
    }
  }

  private void saveSnapshot(UUID tenantId, UUID productId, UUID locationId, String onHand, String available, String source) {
    boolean exists = inventorySnapshotRepository.findTop50ByTenantIdAndProductIdAndLocationIdOrderByCapturedAtDesc(tenantId, productId, locationId).stream().anyMatch(snapshot -> source.equals(snapshot.getSource()));
    if (!exists) {
      inventorySnapshotRepository.save(new InventorySnapshot(tenantId, productId, locationId, new BigDecimal(onHand), new BigDecimal(available), BigDecimal.ZERO, clock.instant(), source, null, clock.instant()));
    }
  }

  private void savePrice(UUID tenantId, UUID productId, String price) {
    boolean exists = priceRuleRepository.findByTenantIdOrderByPriorityAsc(tenantId).stream().anyMatch(rule -> rule.getProductId().equals(productId) && rule.getUnitPrice().compareTo(new BigDecimal(price)) == 0);
    if (!exists) {
      priceRuleRepository.save(new PriceRule(tenantId, productId, null, null, null, BigDecimal.ONE, "EA", new BigDecimal(price), "KZT", Instant.parse("2026-01-01T00:00:00Z"), null, 10, clock.instant()));
    }
  }

  public String fixtureText(String name) {
    try {
      return new String(new ClassPathResource(BASE + name).getInputStream().readAllBytes());
    } catch (IOException ex) {
      throw new IllegalStateException("Unable to load demo fixture " + name, ex);
    }
  }

  private <T> T read(String name, Class<T> type) {
    try {
      return objectMapper.readValue(new ClassPathResource(BASE + name).getInputStream(), type);
    } catch (IOException ex) {
      throw new IllegalStateException("Unable to load demo fixture " + name, ex);
    }
  }

  private <T> T readList(String name, TypeReference<T> type) {
    try {
      return objectMapper.readValue(new ClassPathResource(BASE + name).getInputStream(), type);
    } catch (IOException ex) {
      throw new IllegalStateException("Unable to load demo fixture " + name, ex);
    }
  }

  public record DemoSeedResult(UUID tenantId, UUID customerId, UUID primaryProductId, UUID locationId, String tenantSlug, String primaryProductSku, String locationCode) {}
  record TenantFixture(String slug, String legalName, String status) {}
  record CustomerFixture(String externalRef, String accountCode, String legalName, String displayName, String status, String defaultCurrency) {}
  record ProductsFixture(LocationFixture location, List<ProductFixture> products) {}
  record LocationFixture(String code, String name, String type, String city, String country) {}
  record ProductFixture(String sku, String name, String description, String category, String brand, String manufacturer, String baseUom, String status, BigDecimal cost, String currency, List<AliasFixture> aliases) {}
  record AliasFixture(String aliasType, String rawAlias, String normalizedAlias, BigDecimal confidenceDefault) {}
  record MovementFixture(String productSku, String locationCode, String movementType, BigDecimal quantity, String occurredAt, String sourceType, String sourceReference) {}
}
