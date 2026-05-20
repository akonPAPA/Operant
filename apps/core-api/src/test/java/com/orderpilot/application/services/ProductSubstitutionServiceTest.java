package com.orderpilot.application.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.application.services.ProductSubstitutionService.SubstituteCandidate;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.inventory.InventorySnapshot;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.product.*;
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
@Import({ProductSubstitutionService.class, CoreConfiguration.class})
class ProductSubstitutionServiceTest {
  private static final Instant NOW = Instant.parse("2026-05-20T00:00:00Z");

  @Autowired private ProductSubstitutionService service;
  @Autowired private ProductRepository productRepository;
  @Autowired private ProductAliasRepository aliasRepository;
  @Autowired private OEMReferenceRepository oemRepository;
  @Autowired private ProductSubstituteRepository substituteRepository;
  @Autowired private ProductCompatibilityRepository compatibilityRepository;
  @Autowired private CustomerSubstitutionPreferenceRepository preferenceRepository;
  @Autowired private CustomerAccountRepository customerRepository;
  @Autowired private InventorySnapshotRepository inventoryRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void exactAndOemEquivalentSubstitutesAreReturnedDeterministically() {
    UUID tenantId = UUID.randomUUID();
    CustomerAccount customer = customer(tenantId, "ACME");
    Product original = product(tenantId, "OE-BPAD", "Toyota Camry 2018 OE brake pads", "Toyota");
    Product exact = product(tenantId, "OE-BPAD-NEW", "Toyota superseded OE brake pads", "Toyota");
    Product oem = product(tenantId, "OEM-EQ-1", "OEM equivalent brake pads", "Toyota");
    substitute(tenantId, original, exact, "EXACT_REPLACEMENT", "LOW", false);
    substitute(tenantId, original, oem, "OEM_EQUIVALENT", "LOW", false);
    compatibility(tenantId, exact, "LOW");
    compatibility(tenantId, oem, "LOW");
    inventory(tenantId, exact, "10");
    inventory(tenantId, oem, "10");
    oemRepository.save(new OEMReference(tenantId, original.getId(), "04465-33450", ProductCodeNormalizer.normalize("04465-33450"), "Toyota", NOW));

    List<SubstituteCandidate> candidates = service.suggest(tenantId, null, "04465-33450", "Toyota Camry 2018 brake pads", customer.getId(), new BigDecimal("2"));

    assertThat(candidates).extracting("relationType")
        .contains(ProductSubstitutionService.SubstituteRelationType.EXACT_REPLACEMENT, ProductSubstitutionService.SubstituteRelationType.OEM_EQUIVALENT);
    assertThat(candidates).allSatisfy(candidate -> {
      assertThat(candidate.stockStatus()).isEqualTo(ProductSubstitutionService.StockStatus.AVAILABLE);
      assertThat(candidate.explanation()).contains("matchedSource=PRODUCT_SUBSTITUTE");
    });
  }

  @Test
  void compatibleAlternativeForCamry2018ReturnsAcceptedAndBlockedSignals() {
    UUID tenantId = UUID.randomUUID();
    CustomerAccount customer = customer(tenantId, "ACME");
    Product original = product(tenantId, "TOY-CAM-2018-BPAD-OE", "Original brake pads for Toyota Camry 2018", "Toyota Genuine");
    Product substituteA = product(tenantId, "AFT-CAM-2018-BPAD-A", "Aftermarket compatible substitute A", "RoadMax");
    Product substituteB = product(tenantId, "AFT-CAM-2018-BPAD-B", "Aftermarket compatible substitute B", "SteppeLine");
    aliasRepository.save(new ProductAlias(tenantId, original.getId(), "CUSTOMER_TEXT", "brake pads for Toyota Camry 2018", ProductCodeNormalizer.normalize("brake pads for Toyota Camry 2018"), customer.getId(), new BigDecimal("0.95"), NOW));
    substitute(tenantId, original, substituteA, "COMPATIBLE_ALTERNATIVE", "LOW", false);
    substitute(tenantId, original, substituteB, "COMPATIBLE_ALTERNATIVE", "MEDIUM", true);
    compatibility(tenantId, substituteA, "LOW");
    compatibility(tenantId, substituteB, "MEDIUM");
    inventory(tenantId, substituteA, "75");
    inventory(tenantId, substituteB, "4");
    preferenceRepository.save(new CustomerSubstitutionPreference(tenantId, customer.getId(), original.getId(), null, true, null, null, "Accepted aftermarket", NOW));
    preferenceRepository.save(new CustomerSubstitutionPreference(tenantId, customer.getId(), original.getId(), null, false, null, substituteB.getId(), "Blocked budget option", NOW));

    List<SubstituteCandidate> candidates = service.suggest(tenantId, null, null, "Need brake pads for Toyota Camry 2018, 20 pcs", customer.getId(), new BigDecimal("20"));

    assertThat(candidates).hasSize(2);
    assertThat(candidates.get(0).productId()).isEqualTo(substituteA.getId());
    assertThat(candidates.get(0).relationType()).isEqualTo(ProductSubstitutionService.SubstituteRelationType.CUSTOMER_ACCEPTED);
    assertThat(candidates.get(0).compatibilityMatchReason()).isEqualTo(ProductSubstitutionService.CompatibilityMatchReason.VEHICLE_CONTEXT_MATCH);
    assertThat(candidates.get(0).requiresApproval()).isFalse();
    assertThat(candidates.get(1).productId()).isEqualTo(substituteB.getId());
    assertThat(candidates.get(1).blocked()).isTrue();
    assertThat(candidates.get(1).reasonCode()).isEqualTo("CUSTOMER_BLOCKED_RULE");
  }

  @Test
  void highRiskOrUnverifiedCompatibilityRequiresApproval() {
    UUID tenantId = UUID.randomUUID();
    Product original = product(tenantId, "SRC", "Source", "Brand");
    Product candidate = product(tenantId, "ALT", "Alternative", "AltBrand");
    substitute(tenantId, original, candidate, "COMPATIBLE_ALTERNATIVE", "LOW", false);
    inventory(tenantId, candidate, "5");

    List<SubstituteCandidate> candidates = service.suggest(tenantId, original.getId(), "SRC", "Unknown machine 2020", null, BigDecimal.ONE);

    assertThat(candidates).hasSize(1);
    assertThat(candidates.get(0).requiresApproval()).isTrue();
    assertThat(candidates.get(0).reasonCode()).isEqualTo("COMPATIBILITY_UNVERIFIED");
  }

  @Test
  void tenantSubstitutesDoNotLeakAcrossTenants() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    Product sourceA = product(tenantA, "SRC", "Source A", "Brand");
    Product sourceB = product(tenantB, "SRC", "Source B", "Brand");
    Product substituteB = product(tenantB, "ALT-B", "Tenant B Alternative", "Brand");
    substitute(tenantB, sourceB, substituteB, "EXACT_REPLACEMENT", "LOW", false);

    List<SubstituteCandidate> candidates = service.suggest(tenantA, sourceA.getId(), "SRC", null, null, BigDecimal.ONE);

    assertThat(candidates).isEmpty();
  }

  private CustomerAccount customer(UUID tenantId, String code) {
    return customerRepository.save(new CustomerAccount(tenantId, null, code, code + " LLP", code, null, "ACTIVE", "USD", null, NOW));
  }

  private Product product(UUID tenantId, String sku, String name, String brand) {
    return productRepository.save(new Product(tenantId, sku, name, null, "Brake System", brand, brand, "EA", "ACTIVE", null, "USD", NOW));
  }

  private ProductSubstitute substitute(UUID tenantId, Product source, Product substitute, String type, String risk, boolean requiresApproval) {
    return substituteRepository.save(new ProductSubstitute(tenantId, source.getId(), substitute.getId(), type, risk, requiresApproval, "test", NOW));
  }

  private ProductCompatibility compatibility(UUID tenantId, Product product, String risk) {
    return compatibilityRepository.save(new ProductCompatibility(tenantId, product.getId(), "VEHICLE", "Toyota", "Camry", 2018, 2018, null, "test", risk, NOW));
  }

  private InventorySnapshot inventory(UUID tenantId, Product product, String available) {
    return inventoryRepository.save(new InventorySnapshot(tenantId, product.getId(), UUID.randomUUID(), new BigDecimal(available), new BigDecimal(available), BigDecimal.ZERO, NOW, "TEST", null, NOW));
  }
}
