package com.orderpilot.application.services;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orderpilot.api.dto.Stage2Dtos.ProductRequest;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.product.ProductAliasRepository;
import com.orderpilot.domain.product.ProductRepository;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProductCatalogServiceTest {
  private final ProductRepository productRepository = mock(ProductRepository.class);
  private final ProductAliasRepository aliasRepository = mock(ProductAliasRepository.class);
  private final AuditEventService auditEventService = mock(AuditEventService.class);
  private final ProductCatalogService service = new ProductCatalogService(productRepository, aliasRepository, auditEventService, Clock.systemUTC());

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void preventsDuplicateSkuWithinTenant() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    when(productRepository.existsByTenantIdAndSkuAndDeletedAtIsNull(tenantId, "SKU-1")).thenReturn(true);

    assertThatThrownBy(() -> service.create(new ProductRequest("SKU-1", "Name", null, null, null, null, "EA", "ACTIVE", null, "USD")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SKU already exists");
  }
}