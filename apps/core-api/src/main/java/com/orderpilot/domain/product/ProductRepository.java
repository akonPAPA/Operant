package com.orderpilot.domain.product;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, UUID> {
  List<Product> findByTenantIdAndDeletedAtIsNullOrderBySku(UUID tenantId);
  Optional<Product> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);
  Optional<Product> findByTenantIdAndSkuAndDeletedAtIsNull(UUID tenantId, String sku);
  boolean existsByTenantIdAndSkuAndDeletedAtIsNull(UUID tenantId, String sku);
  List<Product> findTop25ByTenantIdAndDeletedAtIsNullAndSkuContainingIgnoreCaseOrTenantIdAndDeletedAtIsNullAndNameContainingIgnoreCase(UUID tenantIdA, String sku, UUID tenantIdB, String name);
}