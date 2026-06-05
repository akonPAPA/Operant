package com.orderpilot.domain.customer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerContactRepository extends JpaRepository<CustomerContact, UUID> {
  List<CustomerContact> findByTenantIdAndCustomerAccountIdAndDeletedAtIsNull(UUID tenantId, UUID customerAccountId);
  Optional<CustomerContact> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);
}