package com.orderpilot.domain.product;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductCompatibilityRepository extends JpaRepository<ProductCompatibility, UUID> {
  List<ProductCompatibility> findByTenantIdAndProductIdAndActiveTrue(UUID tenantId, UUID productId);
  boolean existsByTenantIdAndProductIdAndMakeAndModelAndYearFromAndYearToAndActiveTrue(UUID tenantId, UUID productId, String make, String model, Integer yearFrom, Integer yearTo);
}
