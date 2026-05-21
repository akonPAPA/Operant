package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage2Dtos.MarginRuleResponse;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.pricing.MarginRule;
import com.orderpilot.domain.pricing.MarginRuleRepository;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/margins")
public class MarginController {
  private final MarginRuleRepository repository;
  public MarginController(MarginRuleRepository repository) { this.repository = repository; }

  @GetMapping
  public List<MarginRuleResponse> list() {
    return repository.findByTenantIdAndActiveTrue(TenantContext.requireTenantId()).stream().map(this::toResponse).toList();
  }

  private MarginRuleResponse toResponse(MarginRule rule) {
    return new MarginRuleResponse(rule.getId(), rule.getCode(), rule.getName(), rule.getProductId(), rule.getCategory(), rule.getCustomerSegmentId(), rule.getMinimumGrossMarginPercent(), rule.getApprovalRequiredBelowPercent());
  }
}