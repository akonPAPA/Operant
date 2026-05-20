package com.orderpilot.domain.reconciliation;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationCaseRepository extends JpaRepository<ReconciliationCase, UUID> {
  Optional<ReconciliationCase> findByIdAndTenantId(UUID id, UUID tenantId);
  Optional<ReconciliationCase> findFirstByTenantIdAndProductIdAndLocationIdAndStatusInOrderByUpdatedAtDesc(UUID tenantId, UUID productId, UUID locationId, Collection<ReconciliationStatus> statuses);
  Page<ReconciliationCase> findByTenantIdOrderByUpdatedAtDesc(UUID tenantId, Pageable pageable);
  long countByTenantIdAndStatus(UUID tenantId, ReconciliationStatus status);
  long countByTenantIdAndSeverityAndStatus(UUID tenantId, ReconciliationSeverity severity, ReconciliationStatus status);
}
