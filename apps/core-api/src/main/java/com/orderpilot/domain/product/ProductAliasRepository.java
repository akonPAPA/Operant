package com.orderpilot.domain.product;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductAliasRepository extends JpaRepository<ProductAlias, UUID> {
  List<ProductAlias> findByTenantIdAndProductIdAndActiveTrueOrderByRawAlias(UUID tenantId, UUID productId);
  List<ProductAlias> findByTenantIdAndNormalizedAliasAndActiveTrue(UUID tenantId, String normalizedAlias);
}
