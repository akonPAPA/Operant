package com.orderpilot.domain.customer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerAccountRepository extends JpaRepository<CustomerAccount, UUID> {
  List<CustomerAccount> findByTenantIdAndDeletedAtIsNullOrderByAccountCode(UUID tenantId);
  Optional<CustomerAccount> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);
  Optional<CustomerAccount> findByTenantIdAndAccountCodeAndDeletedAtIsNull(UUID tenantId, String accountCode);
  boolean existsByTenantIdAndAccountCodeAndDeletedAtIsNull(UUID tenantId, String accountCode);
}