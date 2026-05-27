package com.orderpilot.application.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.imports.ImportStagingRow;
import com.orderpilot.domain.location.LocationRepository;
import com.orderpilot.domain.pricing.DiscountRuleRepository;
import com.orderpilot.domain.pricing.MarginRuleRepository;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.product.OEMReferenceRepository;
import com.orderpilot.domain.product.ProductAliasRepository;
import com.orderpilot.domain.product.ProductCompatibilityRepository;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.product.ProductSubstituteRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ImportValidationServiceTest {
  private final ProductRepository productRepository = mock(ProductRepository.class);
  private final CustomerAccountRepository customerAccountRepository = mock(CustomerAccountRepository.class);
  private final LocationRepository locationRepository = mock(LocationRepository.class);
  private final ProductAliasRepository productAliasRepository = mock(ProductAliasRepository.class);
  private final OEMReferenceRepository oemReferenceRepository = mock(OEMReferenceRepository.class);
  private final ProductSubstituteRepository productSubstituteRepository = mock(ProductSubstituteRepository.class);
  private final ProductCompatibilityRepository productCompatibilityRepository = mock(ProductCompatibilityRepository.class);
  private final PriceRuleRepository priceRuleRepository = mock(PriceRuleRepository.class);
  private final DiscountRuleRepository discountRuleRepository = mock(DiscountRuleRepository.class);
  private final MarginRuleRepository marginRuleRepository = mock(MarginRuleRepository.class);
  private final ImportValidationService service = new ImportValidationService(new JsonSupport(new ObjectMapper()), productRepository, customerAccountRepository, locationRepository, productAliasRepository, oemReferenceRepository, productSubstituteRepository, productCompatibilityRepository, priceRuleRepository, discountRuleRepository, marginRuleRepository);

  @Test
  void validatesProductImportRow() {
    UUID tenantId = UUID.randomUUID();
    ImportStagingRow row = new ImportStagingRow(tenantId, UUID.randomUUID(), 1, "{\"sku\":\"PAD-1\",\"name\":\"Brake Pad\",\"baseUom\":\"EA\",\"cost\":12.5}", Instant.now());
    when(productRepository.existsByTenantIdAndSkuAndDeletedAtIsNull(tenantId, "PAD-1")).thenReturn(false);

    ImportValidationService.RowValidationResult result = service.validate(tenantId, "PRODUCTS", row);

    assertThat(result.validationStatus()).isEqualTo("VALID");
    assertThat(result.validationErrors()).isNull();
  }

  @Test
  void rejectsBadInventoryImportRow() {
    UUID tenantId = UUID.randomUUID();
    ImportStagingRow row = new ImportStagingRow(tenantId, UUID.randomUUID(), 1, "{\"sku\":\"MISSING\",\"locationCode\":\"WH1\",\"quantityOnHand\":-3}", Instant.now());
    when(productRepository.findByTenantIdAndSkuAndDeletedAtIsNull(tenantId, "MISSING")).thenReturn(Optional.empty());
    when(locationRepository.findByTenantIdAndCode(tenantId, "WH1")).thenReturn(Optional.empty());

    ImportValidationService.RowValidationResult result = service.validate(tenantId, "INVENTORY", row);

    assertThat(result.validationStatus()).isEqualTo("INVALID");
    assertThat(result.validationErrors()).contains("quantityOnHand");
  }
}
