package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage2Dtos.DiscountRuleResponse;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.pricing.DiscountRule;
import com.orderpilot.domain.pricing.DiscountRuleRepository;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/discounts")
public class DiscountController {
  private final DiscountRuleRepository repository;
  public DiscountController(DiscountRuleRepository repository) { this.repository = repository; }

  @GetMapping
  public List<DiscountRuleResponse> list() {
    return repository.findByTenantIdAndActiveTrue(TenantContext.requireTenantId()).stream().map(this::toResponse).toList();
  }

  private DiscountRuleResponse toResponse(DiscountRule rule) {
    return new DiscountRuleResponse(rule.getId(), rule.getCode(), rule.getName(), rule.getCustomerAccountId(), rule.getCustomerSegmentId(), rule.getProductId(), rule.getMaxDiscountPercent(), rule.getRequiresApprovalAbovePercent());
  }
}