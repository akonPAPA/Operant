package com.orderpilot.application.services.validation;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.pricing.DiscountRule;
import com.orderpilot.domain.pricing.DiscountRuleRepository;
import com.orderpilot.domain.validation.DiscountCheckResult;
import com.orderpilot.domain.validation.DiscountCheckResultRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiscountValidationService {
  private final DiscountRuleRepository ruleRepository; private final DiscountCheckResultRepository resultRepository; private final ApprovalRequirementService approvalService; private final Clock clock;
  public DiscountValidationService(DiscountRuleRepository ruleRepository, DiscountCheckResultRepository resultRepository, ApprovalRequirementService approvalService, Clock clock) { this.ruleRepository=ruleRepository; this.resultRepository=resultRepository; this.approvalService=approvalService; this.clock=clock; }
  @Transactional
  public DiscountCheckResult check(UUID runId, UUID lineId, UUID customerId, UUID productId, BigDecimal requestedDiscount) {
    UUID tenantId = TenantContext.requireTenantId();
    if (requestedDiscount == null) return save(tenantId, runId, lineId, customerId, productId, null, null, null, false, "ALLOWED");
    DiscountRule rule = ruleRepository.findByTenantIdAndActiveTrue(tenantId).stream().filter(r -> (r.getProductId() == null || r.getProductId().equals(productId)) && (r.getCustomerAccountId() == null || r.getCustomerAccountId().equals(customerId))).findFirst().orElse(null);
    if (rule == null) return save(tenantId, runId, lineId, customerId, productId, null, requestedDiscount, null, false, "NO_RULE");
    boolean above = requestedDiscount.compareTo(rule.getMaxDiscountPercent()) > 0 || requestedDiscount.compareTo(rule.getRequiresApprovalAbovePercent()) > 0;
    if (above) approvalService.create(runId, lineId, "DISCOUNT_EXCEEDS_RULE", "HIGH", "Requested discount exceeds deterministic discount rule");
    return save(tenantId, runId, lineId, customerId, productId, rule.getId(), requestedDiscount, rule.getMaxDiscountPercent(), above, above ? "EXCEEDS_RULE" : "ALLOWED");
  }
  @Transactional(readOnly = true) public List<DiscountCheckResult> list(UUID runId) { return resultRepository.findByTenantIdAndValidationRunId(TenantContext.requireTenantId(), runId); }
  public BigDecimal extractRequestedDiscount(Map<String, Object> resultJson) { Object value = resultJson.get("discountPercent"); return value == null ? null : new BigDecimal(value.toString()); }
  private DiscountCheckResult save(UUID tenantId, UUID runId, UUID lineId, UUID customerId, UUID productId, UUID ruleId, BigDecimal requested, BigDecimal max, boolean approval, String status) { return resultRepository.save(new DiscountCheckResult(tenantId, runId, lineId, customerId, productId, ruleId, requested, max, approval, status, clock.instant())); }
}
