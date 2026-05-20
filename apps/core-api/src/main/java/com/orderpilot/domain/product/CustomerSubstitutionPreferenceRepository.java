package com.orderpilot.domain.product;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerSubstitutionPreferenceRepository extends JpaRepository<CustomerSubstitutionPreference, UUID> {
  List<CustomerSubstitutionPreference> findByTenantIdAndCustomerAccountId(UUID tenantId, UUID customerAccountId);
  boolean existsByTenantIdAndCustomerAccountIdAndProductIdAndBlockedSubstituteProductId(UUID tenantId, UUID customerAccountId, UUID productId, UUID blockedSubstituteProductId);
  boolean existsByTenantIdAndCustomerAccountIdAndProductIdAndAllowAftermarketTrue(UUID tenantId, UUID customerAccountId, UUID productId);
}
