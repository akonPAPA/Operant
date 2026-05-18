package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage2Dtos.PriceRuleRequest;
import com.orderpilot.api.dto.Stage2Dtos.PriceRuleResponse;
import com.orderpilot.application.services.PricingRuleService;
import com.orderpilot.domain.pricing.PriceRule;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pricing")
public class PricingController {
  private final PricingRuleService service;
  public PricingController(PricingRuleService service) { this.service = service; }

  @GetMapping("/rules") public List<PriceRuleResponse> list() { return service.list().stream().map(this::toResponse).toList(); }
  @PostMapping("/rules") public PriceRuleResponse create(@RequestBody PriceRuleRequest request) { return toResponse(service.create(request)); }
  private PriceRuleResponse toResponse(PriceRule rule) { return new PriceRuleResponse(rule.getId(), rule.getProductId(), rule.getUnitPrice(), rule.getCurrency()); }
}