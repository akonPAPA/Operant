package com.orderpilot.domain.product;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductSubstituteRepository extends JpaRepository<ProductSubstitute, UUID> {
  List<ProductSubstitute> findByTenantIdAndSourceProductIdAndActiveTrue(UUID tenantId, UUID sourceProductId);
  boolean existsByTenantIdAndSourceProductIdAndSubstituteProductIdAndSubstituteTypeAndActiveTrue(UUID tenantId, UUID sourceProductId, UUID substituteProductId, String substituteType);
}
