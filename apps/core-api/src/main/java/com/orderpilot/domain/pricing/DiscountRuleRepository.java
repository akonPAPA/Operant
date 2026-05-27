package com.orderpilot.domain.pricing;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiscountRuleRepository extends JpaRepository<DiscountRule, UUID> {
  List<DiscountRule> findByTenantIdAndActiveTrue(UUID tenantId);
  boolean existsByTenantIdAndCodeAndActiveTrue(UUID tenantId, String code);
}
