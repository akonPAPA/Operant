package com.orderpilot.application.services;

import com.orderpilot.api.dto.Stage2Dtos.CustomerRequest;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerAccountService {
  private final CustomerAccountRepository repository;
  private final AuditEventService auditEventService;
  private final Clock clock;

  public CustomerAccountService(CustomerAccountRepository repository, AuditEventService auditEventService, Clock clock) {
    this.repository = repository;
    this.auditEventService = auditEventService;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<CustomerAccount> list() {
    return repository.findByTenantIdAndDeletedAtIsNullOrderByAccountCode(TenantContext.requireTenantId());
  }

  @Transactional(readOnly = true)
  public CustomerAccount get(UUID id) {
    return repository.findByIdAndTenantIdAndDeletedAtIsNull(id, TenantContext.requireTenantId())
        .orElseThrow(() -> new IllegalArgumentException("Customer account not found"));
  }

  @Transactional
  public CustomerAccount create(CustomerRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    if (repository.existsByTenantIdAndAccountCodeAndDeletedAtIsNull(tenantId, request.accountCode())) {
      throw new IllegalArgumentException("Customer account code already exists for tenant");
    }
    CustomerAccount customer = new CustomerAccount(tenantId, request.externalRef(), request.accountCode(), request.legalName(), request.displayName(), request.segmentId(), request.status(), request.defaultCurrency(), request.defaultLocationId(), clock.instant());
    CustomerAccount saved = repository.save(customer);
    auditEventService.record("customer_account.created", "customer_account", saved.getId().toString(), null, "{\"source\":\"core-api\"}");
    return saved;
  }

  @Transactional
  public CustomerAccount update(UUID id, CustomerRequest request) {
    CustomerAccount customer = get(id);
    customer.update(request.legalName(), request.displayName(), request.segmentId(), request.status(), request.defaultCurrency(), request.defaultLocationId(), clock.instant());
    auditEventService.record("customer_account.updated", "customer_account", id.toString(), null, "{\"source\":\"core-api\"}");
    return customer;
  }
}