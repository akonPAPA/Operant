package com.orderpilot.application.services.workspace;

import com.orderpilot.domain.pricing.MarginRule;
import com.orderpilot.domain.pricing.MarginRuleRepository;
import com.orderpilot.domain.product.Product;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuoteMarginValidationService {
  private final MarginRuleRepository repository;

  public QuoteMarginValidationService(MarginRuleRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public MarginValidation validate(UUID tenantId, Product product, BigDecimal unitPrice, BigDecimal discountPercent) {
    if (product == null || product.getCost() == null || unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) <= 0) {
      return new MarginValidation(null, false, false, "MARGIN_NOT_EVALUATED");
    }
    BigDecimal discount = discountPercent == null ? BigDecimal.ZERO : discountPercent;
    BigDecimal netUnitPrice = unitPrice.multiply(BigDecimal.ONE.subtract(discount.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP)));
    if (netUnitPrice.compareTo(BigDecimal.ZERO) <= 0) {
      return new MarginValidation(new BigDecimal("-100.00"), true, true, "MARGIN_BELOW_GUARDRAIL");
    }
    BigDecimal marginPercent = netUnitPrice.subtract(product.getCost())
        .divide(netUnitPrice, 6, RoundingMode.HALF_UP)
        .multiply(new BigDecimal("100"))
        .setScale(2, RoundingMode.HALF_UP);
    Optional<MarginRule> rule = repository.findByTenantIdAndActiveTrue(tenantId).stream()
        .filter(candidate -> candidate.getProductId() == null || candidate.getProductId().equals(product.getId()))
        .filter(candidate -> candidate.getCategory() == null || candidate.getCategory().equalsIgnoreCase(product.getCategory()))
        .sorted(Comparator.comparing((MarginRule candidate) -> candidate.getProductId() == null ? 1 : 0)
            .thenComparing(candidate -> candidate.getCategory() == null ? 1 : 0))
        .findFirst();
    if (rule.isEmpty()) {
      return new MarginValidation(marginPercent, false, false, "MARGIN_RULE_NOT_CONFIGURED");
    }
    boolean approval = marginPercent.compareTo(rule.get().getApprovalRequiredBelowPercent()) < 0;
    boolean violation = marginPercent.compareTo(rule.get().getMinimumGrossMarginPercent()) < 0;
    return new MarginValidation(marginPercent, approval, violation, violation ? "MARGIN_BELOW_GUARDRAIL" : approval ? "MARGIN_APPROVAL_REQUIRED" : "MARGIN_SAFE");
  }

  public record MarginValidation(BigDecimal marginPercent, boolean approvalRequired, boolean violation, String reasonCode) {}
}
