package com.orderpilot.application.services.validation;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.pricing.MarginRule;
import com.orderpilot.domain.pricing.MarginRuleRepository;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.validation.MarginCheckResult;
import com.orderpilot.domain.validation.MarginCheckResultRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MarginValidationService {
  private final MarginRuleRepository ruleRepository; private final ProductRepository productRepository; private final MarginCheckResultRepository resultRepository; private final ValidationIssueService issueService; private final ApprovalRequirementService approvalService; private final Clock clock;
  public MarginValidationService(MarginRuleRepository ruleRepository, ProductRepository productRepository, MarginCheckResultRepository resultRepository, ValidationIssueService issueService, ApprovalRequirementService approvalService, Clock clock) { this.ruleRepository=ruleRepository; this.productRepository=productRepository; this.resultRepository=resultRepository; this.issueService=issueService; this.approvalService=approvalService; this.clock=clock; }

  @Transactional
  public MarginCheckResult check(UUID runId, UUID extractionResultId, UUID lineId, UUID productId, BigDecimal unitPrice) {
    UUID tenantId = TenantContext.requireTenantId();
    if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) <= 0) {
      issueService.open(runId, extractionResultId, lineId, null, "MARGIN_BELOW_GUARDRAIL", "WARNING", "Margin cannot be computed because unit price is unknown", "{}");
      return save(tenantId, runId, lineId, productId, null, unitPrice, null, null, null, null, false, "PRICE_UNKNOWN");
    }
    Product product = productId == null ? null : productRepository.findByIdAndTenantIdAndDeletedAtIsNull(productId, tenantId).orElse(null);
    BigDecimal cost = product == null ? null : product.getCost();
    if (cost == null) return save(tenantId, runId, lineId, productId, null, unitPrice, null, null, null, null, false, "COST_UNKNOWN");
    BigDecimal margin = unitPrice.subtract(cost).divide(unitPrice, 6, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
    MarginRule rule = ruleRepository.findByTenantIdAndActiveTrue(tenantId).stream().filter(r -> r.getProductId() == null || r.getProductId().equals(productId)).findFirst().orElse(null);
    if (rule == null) return save(tenantId, runId, lineId, productId, null, unitPrice, cost, margin, null, null, false, "PASS");
    boolean belowApproval = margin.compareTo(rule.getApprovalRequiredBelowPercent()) < 0;
    boolean belowMinimum = margin.compareTo(rule.getMinimumGrossMarginPercent()) < 0;
    if (belowApproval) approvalService.create(runId, lineId, "MARGIN_BELOW_GUARDRAIL", "HIGH", "Gross margin is below the approval threshold");
    if (belowMinimum) issueService.open(runId, extractionResultId, lineId, null, "MARGIN_BELOW_GUARDRAIL", "CRITICAL", "Gross margin is below minimum guardrail", "{}");
    String status = belowMinimum ? "BELOW_MINIMUM" : belowApproval ? "BELOW_APPROVAL_THRESHOLD" : "PASS";
    return save(tenantId, runId, lineId, productId, rule.getId(), unitPrice, cost, margin, rule.getMinimumGrossMarginPercent(), rule.getApprovalRequiredBelowPercent(), belowApproval, status);
  }

  @Transactional(readOnly = true) public List<MarginCheckResult> list(UUID runId) { return resultRepository.findByTenantIdAndValidationRunId(TenantContext.requireTenantId(), runId); }
  private MarginCheckResult save(UUID tenantId, UUID runId, UUID lineId, UUID productId, UUID ruleId, BigDecimal price, BigDecimal cost, BigDecimal margin, BigDecimal minimum, BigDecimal approvalBelow, boolean approval, String status) { return resultRepository.save(new MarginCheckResult(tenantId, runId, lineId, productId, ruleId, price, cost, margin, minimum, approvalBelow, approval, status, clock.instant())); }
}
