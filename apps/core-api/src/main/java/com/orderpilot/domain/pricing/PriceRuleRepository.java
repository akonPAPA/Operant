package com.orderpilot.domain.pricing;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceRuleRepository extends JpaRepository<PriceRule, UUID> {
  List<PriceRule> findByTenantIdOrderByPriorityAsc(UUID tenantId);

  // Tenant- and product-scoped lookup for per-line price validation. Backed by
  // idx_price_rule_tenant_product(tenant_id, product_id). Ordered by priority to match
  // the input ordering previously produced by findByTenantIdOrderByPriorityAsc before
  // the explicit rank/priority sort in PricingValidationService.
  List<PriceRule> findByTenantIdAndProductIdOrderByPriorityAsc(UUID tenantId, UUID productId);
  boolean existsByTenantIdAndProductIdAndCustomerAccountIdAndUnitPriceAndActiveTrue(UUID tenantId, UUID productId, UUID customerAccountId, java.math.BigDecimal unitPrice);
}
