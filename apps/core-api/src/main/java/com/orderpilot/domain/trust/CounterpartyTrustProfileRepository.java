package com.orderpilot.domain.trust;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CounterpartyTrustProfileRepository extends JpaRepository<CounterpartyTrustProfile, UUID> {
  Optional<CounterpartyTrustProfile> findByTenantIdAndCustomerAccountId(UUID tenantId, UUID customerAccountId);

  Optional<CounterpartyTrustProfile> findByIdAndTenantId(UUID id, UUID tenantId);

  boolean existsByTenantIdAndCustomerAccountId(UUID tenantId, UUID customerAccountId);
}
