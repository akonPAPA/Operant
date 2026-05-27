package com.orderpilot.application.services.workspace;

import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerResolutionService {
  private final CustomerAccountRepository repository;

  public CustomerResolutionService(CustomerAccountRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public Optional<CustomerAccount> resolve(UUID tenantId, String customerExternalRef, String customerName) {
    if (!isBlank(customerExternalRef)) {
      Optional<CustomerAccount> byAccountCode = repository.findByTenantIdAndAccountCodeAndDeletedAtIsNull(tenantId, customerExternalRef.trim());
      if (byAccountCode.isPresent()) {
        return byAccountCode;
      }
      return repository.findByTenantIdAndDeletedAtIsNullOrderByAccountCode(tenantId).stream()
          .filter(customer -> customerExternalRef.trim().equalsIgnoreCase(customer.getExternalRef()))
          .findFirst();
    }
    if (!isBlank(customerName)) {
      String normalized = customerName.trim();
      return repository.findByTenantIdAndDeletedAtIsNullOrderByAccountCode(tenantId).stream()
          .filter(customer -> normalized.equalsIgnoreCase(customer.getDisplayName()) || normalized.equalsIgnoreCase(customer.getLegalName()) || normalized.equalsIgnoreCase(customer.getAccountCode()))
          .findFirst();
    }
    return Optional.empty();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
