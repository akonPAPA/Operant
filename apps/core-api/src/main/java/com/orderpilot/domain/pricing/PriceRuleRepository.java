package com.orderpilot.domain.pricing;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceRuleRepository extends JpaRepository<PriceRule, UUID> {
  List<PriceRule> findByTenantIdOrderByPriorityAsc(UUID tenantId);
  boolean existsByTenantIdAndProductIdAndCustomerAccountIdAndUnitPriceAndActiveTrue(UUID tenantId, UUID productId, UUID customerAccountId, java.math.BigDecimal unitPrice);
}
