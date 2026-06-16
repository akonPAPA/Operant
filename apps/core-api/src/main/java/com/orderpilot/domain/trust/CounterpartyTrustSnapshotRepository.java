package com.orderpilot.domain.trust;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CounterpartyTrustSnapshotRepository extends JpaRepository<CounterpartyTrustSnapshot, UUID> {
  // Bounded, tenant-scoped recent snapshots (caller supplies a clamped Pageable).
  List<CounterpartyTrustSnapshot> findByTenantIdAndCustomerAccountIdOrderByCreatedAtDesc(
      UUID tenantId, UUID customerAccountId, Pageable pageable);

  // Idempotency guard: at most one snapshot per (counterparty, source type, source ref).
  boolean existsByTenantIdAndCustomerAccountIdAndSourceTypeAndSourceRefId(
      UUID tenantId, UUID customerAccountId, CounterpartyTrustSourceType sourceType, UUID sourceRefId);
}
