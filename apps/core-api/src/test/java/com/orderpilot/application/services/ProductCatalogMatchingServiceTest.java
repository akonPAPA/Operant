package com.orderpilot.application.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.application.services.ProductCatalogMatchingService.ProductMatchType;
import com.orderpilot.domain.product.OEMReference;
import com.orderpilot.domain.product.OEMReferenceRepository;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductAlias;
import com.orderpilot.domain.product.ProductAliasRepository;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ProductCatalogMatchingService.class, CoreConfiguration.class})
class ProductCatalogMatchingServiceTest {
  private static final Instant NOW = Instant.parse("2026-05-20T00:00:00Z");

  @Autowired private ProductCatalogMatchingService service;
  @Autowired private ProductRepository productRepository;
  @Autowired private ProductAliasRepository aliasRepository;
  @Autowired private OEMReferenceRepository oemRepository;

  @Test
  void exactSkuAliasAndOemResolveWithinTenantOnly() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    Product brake = product(tenantA, "BRK-CAMRY-2018-AFT-A", "Toyota Camry 2018 Aftermarket Brake Pads A", "ACTIVE");
    Product filter = product(tenantA, "FILTER-17801-0H050", "Toyota Air Filter 17801-0H050", "ACTIVE");
    Product tenantBProduct = product(tenantB, "TENANT-B-PART", "Tenant B Part", "ACTIVE");
    alias(tenantA, brake.getId(), "AB-1209", null);
    alias(tenantB, tenantBProduct.getId(), "AB-1209", null);
    oem(tenantA, filter.getId(), "17801-0H050");
    oem(tenantB, tenantBProduct.getId(), "17801-0H050");

    assertThat(service.match(tenantA, "BRK CAMRY 2018 AFT A", null, null).matchType()).isEqualTo(ProductMatchType.SKU_EXACT);
    assertThat(service.match(tenantA, "AB1209", null, null).productId()).isEqualTo(brake.getId());
    assertThat(service.match(tenantA, "17801 / 0h050", null, null).productId()).isEqualTo(filter.getId());
    assertThat(service.match(tenantA, "AB-1209", null, null).productId()).isNotEqualTo(tenantBProduct.getId());
    assertThat(service.match(tenantA, "17801-0H050", null, null).productId()).isNotEqualTo(tenantBProduct.getId());
  }

  @Test
  void customerSpecificAliasIsPreferredAndDoesNotLeakToOtherCustomers() {
    UUID tenantId = UUID.randomUUID();
    UUID customerA = UUID.randomUUID();
    UUID customerB = UUID.randomUUID();
    Product global = product(tenantId, "GLOBAL-001", "Global", "ACTIVE");
    Product customerSpecific = product(tenantId, "CUSTOMER-001", "Customer Specific", "ACTIVE");
    Product customerOnly = product(tenantId, "CUSTOMER-ONLY", "Customer Only", "ACTIVE");
    alias(tenantId, global.getId(), "CAMRY-BRAKEPAD-2018", null);
    alias(tenantId, customerSpecific.getId(), "CAMRY-BRAKEPAD-2018", customerA);
    alias(tenantId, customerOnly.getId(), "PRIVATE-SKU", customerA);

    assertThat(service.match(tenantId, "CAMRY BRAKEPAD 2018", null, customerA).productId()).isEqualTo(customerSpecific.getId());
    assertThat(service.match(tenantId, "CAMRY-BRAKEPAD-2018", null, customerB).productId()).isEqualTo(global.getId());
    assertThat(service.match(tenantId, "PRIVATE-SKU", null, customerB).matchType()).isEqualTo(ProductMatchType.NO_MATCH);
  }

  @Test
  void inactiveProductsAreSkippedAndAmbiguousMatchesRequireReview() {
    UUID tenantId = UUID.randomUUID();
    Product inactive = product(tenantId, "OLD-001", "Inactive", "INACTIVE");
    Product first = product(tenantId, "DUP-001-A", "First", "ACTIVE");
    Product second = product(tenantId, "DUP-001-B", "Second", "ACTIVE");
    alias(tenantId, inactive.getId(), "OLD-ALIAS", null);
    alias(tenantId, first.getId(), "DUP-ALIAS", null);
    alias(tenantId, second.getId(), "DUP-ALIAS", null);

    assertThat(service.match(tenantId, "OLD-001", null, null).matchType()).isEqualTo(ProductMatchType.NO_MATCH);
    var ambiguous = service.match(tenantId, "DUP-ALIAS", null, null);
    assertThat(ambiguous.matchType()).isEqualTo(ProductMatchType.AMBIGUOUS);
    assertThat(ambiguous.requiresReview()).isTrue();
    assertThat(ambiguous.candidateProductIds()).containsExactlyInAnyOrder(first.getId(), second.getId());
    assertThat(service.match(tenantId, "NO-SUCH-SKU", null, null).matchType()).isEqualTo(ProductMatchType.NO_MATCH);
  }

  private Product product(UUID tenantId, String sku, String name, String status) {
    return productRepository.save(new Product(tenantId, sku, name, null, "PARTS", null, null, "EA", status, null, "USD", NOW));
  }

  private ProductAlias alias(UUID tenantId, UUID productId, String alias, UUID customerAccountId) {
    return aliasRepository.save(new ProductAlias(tenantId, productId, "CUSTOMER_SKU", alias, ProductCodeNormalizer.normalize(alias), customerAccountId, new BigDecimal("0.95"), NOW));
  }

  private OEMReference oem(UUID tenantId, UUID productId, String oemCode) {
    return oemRepository.save(new OEMReference(tenantId, productId, oemCode, ProductCodeNormalizer.normalize(oemCode), "Toyota", NOW));
  }
}
