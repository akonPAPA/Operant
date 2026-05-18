package com.orderpilot.application.services;

import com.orderpilot.api.dto.Stage2Dtos.PriceRuleRequest;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.pricing.PriceRule;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PricingRuleService {
  private final PriceRuleRepository repository;
  private final AuditEventService auditEventService;
  private final Clock clock;

  public PricingRuleService(PriceRuleRepository repository, AuditEventService auditEventService, Clock clock) {
    this.repository = repository;
    this.auditEventService = auditEventService;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<PriceRule> list() {
    return repository.findByTenantIdOrderByPriorityAsc(TenantContext.requireTenantId());
  }

  @Transactional
  public PriceRule create(PriceRuleRequest request) {
    PriceRule rule = new PriceRule(TenantContext.requireTenantId(), request.productId(), request.customerAccountId(), request.customerSegmentId(), request.locationId(), request.minQuantity(), request.uom(), request.unitPrice(), request.currency(), request.activeFrom(), request.activeTo(), request.priority(), clock.instant());
    PriceRule saved = repository.save(rule);
    auditEventService.record("price_rule.created", "price_rule", saved.getId().toString(), null, "{\"source\":\"core-api\"}");
    return saved;
  }
}