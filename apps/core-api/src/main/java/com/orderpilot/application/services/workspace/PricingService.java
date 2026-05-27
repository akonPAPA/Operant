package com.orderpilot.application.services.workspace;

import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.pricing.PriceRule;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.product.Product;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PricingService {
  private final PriceRuleRepository repository;

  public PricingService(PriceRuleRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public Optional<PriceRule> selectPrice(UUID tenantId, Product product, CustomerAccount customer, UUID locationId, BigDecimal quantity, String uom) {
    BigDecimal requestedQuantity = quantity == null ? BigDecimal.ZERO : quantity;
    String requestedUom = uom == null ? "" : uom;
    return repository.findByTenantIdOrderByPriorityAsc(tenantId).stream()
        .filter(PriceRule::isActive)
        .filter(rule -> rule.getProductId().equals(product.getId()))
        .filter(rule -> rule.getCustomerAccountId() == null || (customer != null && rule.getCustomerAccountId().equals(customer.getId())))
        .filter(rule -> rule.getLocationId() == null || rule.getLocationId().equals(locationId))
        .filter(rule -> rule.getUom().equalsIgnoreCase(requestedUom))
        .filter(rule -> rule.getMinQuantity().compareTo(requestedQuantity) <= 0)
        .sorted(Comparator
            .comparing((PriceRule rule) -> rule.getCustomerAccountId() == null ? 1 : 0)
            .thenComparing(rule -> rule.getLocationId() == null ? 1 : 0)
            .thenComparing(PriceRule::getPriority)
            .thenComparing(PriceRule::getMinQuantity, Comparator.reverseOrder()))
        .findFirst();
  }
}
