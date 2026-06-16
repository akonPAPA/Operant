package com.orderpilot.domain.trust;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CounterpartyTrustSignalRepository extends JpaRepository<CounterpartyTrustSignal, UUID> {
  // Bounded, tenant-scoped recent signals (caller supplies a clamped Pageable).
  List<CounterpartyTrustSignal> findByTenantIdAndCustomerAccountIdOrderByCreatedAtDesc(
      UUID tenantId, UUID customerAccountId, Pageable pageable);
}
