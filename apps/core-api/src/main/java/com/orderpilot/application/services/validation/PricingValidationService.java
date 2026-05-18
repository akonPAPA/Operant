package com.orderpilot.application.services.validation;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.extraction.ExtractedLineItem;
import com.orderpilot.domain.pricing.PriceRule;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.validation.PriceCheckResult;
import com.orderpilot.domain.validation.PriceCheckResultRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PricingValidationService {
  private final PriceRuleRepository priceRuleRepository; private final CustomerAccountRepository customerRepository; private final PriceCheckResultRepository resultRepository; private final ValidationIssueService issueService; private final Clock clock;
  public PricingValidationService(PriceRuleRepository priceRuleRepository, CustomerAccountRepository customerRepository, PriceCheckResultRepository resultRepository, ValidationIssueService issueService, Clock clock) { this.priceRuleRepository=priceRuleRepository; this.customerRepository=customerRepository; this.resultRepository=resultRepository; this.issueService=issueService; this.clock=clock; }

  @Transactional
  public PriceCheckResult check(UUID validationRunId, UUID extractionResultId, ExtractedLineItem line, UUID productId, UUID customerAccountId, String uom) {
    UUID tenantId = TenantContext.requireTenantId();
    BigDecimal requested = line.getNormalizedQuantity() == null ? BigDecimal.ONE : line.getNormalizedQuantity();
    UUID segmentId = customerAccountId == null ? null : customerRepository.findByIdAndTenantIdAndDeletedAtIsNull(customerAccountId, tenantId).map(CustomerAccount::getSegmentId).orElse(null);
    Instant now = clock.instant();
    List<PriceRule> matches = priceRuleRepository.findByTenantIdOrderByPriorityAsc(tenantId).stream()
        .filter(r -> r.isActive() && productId != null && productId.equals(r.getProductId()))
        .filter(r -> r.getMinQuantity() == null || requested.compareTo(r.getMinQuantity()) >= 0)
        .filter(r -> uom == null || r.getUom() == null || r.getUom().equalsIgnoreCase(uom))
        .filter(r -> !r.getActiveFrom().isAfter(now) && (r.getActiveTo() == null || r.getActiveTo().isAfter(now)))
        .filter(r -> r.getCustomerAccountId() == null || r.getCustomerAccountId().equals(customerAccountId))
        .filter(r -> r.getCustomerSegmentId() == null || r.getCustomerSegmentId().equals(segmentId))
        .sorted(Comparator.comparingInt((PriceRule r) -> r.getCustomerAccountId() != null ? 0 : r.getCustomerSegmentId() != null ? 1 : 2).thenComparingInt(PriceRule::getPriority))
        .toList();
    if (matches.isEmpty()) {
      issueService.open(validationRunId, extractionResultId, line.getId(), null, "PRICE_NOT_FOUND", "ERROR", "No applicable price rule was found", "{}");
      return save(tenantId, validationRunId, line.getId(), productId, customerAccountId, null, requested, uom, null, null, "PRICE_NOT_FOUND");
    }
    PriceRule first = matches.get(0);
    long equalRank = matches.stream().filter(r -> r.getPriority() == first.getPriority()).count();
    if (equalRank > 1) issueService.open(validationRunId, extractionResultId, line.getId(), null, "PRICE_NOT_FOUND", "WARNING", "Multiple equal-priority price rules require review", "{}");
    return save(tenantId, validationRunId, line.getId(), productId, customerAccountId, first.getId(), requested, first.getUom(), first.getUnitPrice(), first.getCurrency(), equalRank > 1 ? "MULTIPLE_RULES" : "PRICE_FOUND");
  }

  @Transactional(readOnly = true)
  public List<PriceCheckResult> list(UUID validationRunId) { return resultRepository.findByTenantIdAndValidationRunId(TenantContext.requireTenantId(), validationRunId); }

  private PriceCheckResult save(UUID tenantId, UUID runId, UUID lineId, UUID productId, UUID customerId, UUID ruleId, BigDecimal requested, String uom, BigDecimal price, String currency, String status) {
    return resultRepository.save(new PriceCheckResult(tenantId, runId, lineId, productId, customerId, ruleId, requested, uom, price, currency, status, clock.instant()));
  }
}
