package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage2Dtos.CustomerContactResponse;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.customer.CustomerContactRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-06E read-only contact listing for the channel identity link flow.
 *
 * <p>Exposes a minimal contact summary (name, type, preferred flag) scoped to a customer account;
 * direct contact details (email/phone) are excluded. Tenant isolation is enforced server-side: the
 * account must belong to the current tenant before any contacts are returned.
 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}/contacts")
public class CustomerContactController {
  private final CustomerAccountRepository accountRepository;
  private final CustomerContactRepository contactRepository;

  public CustomerContactController(
      CustomerAccountRepository accountRepository,
      CustomerContactRepository contactRepository) {
    this.accountRepository = accountRepository;
    this.contactRepository = contactRepository;
  }

  @GetMapping
  public List<CustomerContactResponse> list(@PathVariable UUID customerId) {
    UUID tenantId = TenantContext.requireTenantId();
    accountRepository
        .findByIdAndTenantIdAndDeletedAtIsNull(customerId, tenantId)
        .orElseThrow(() -> new IllegalArgumentException("Customer account not found"));
    return contactRepository
        .findByTenantIdAndCustomerAccountIdAndDeletedAtIsNull(tenantId, customerId)
        .stream()
        .map(c -> new CustomerContactResponse(
            c.getId(), c.getCustomerAccountId(), c.getContactType(),
            c.getFullName(), c.isPreferred(), c.isActive()))
        .toList();
  }
}
