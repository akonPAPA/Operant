package com.orderpilot.domain.incident;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-53 — tenant-scoped storage/listing for incident records.
 */
public interface IncidentRecordRepository extends JpaRepository<IncidentRecord, UUID> {
  /** Tenant-scoped lookup: an incident can only be found (and acted on) within its own tenant. */
  Optional<IncidentRecord> findByIdAndTenantId(UUID id, UUID tenantId);

  List<IncidentRecord> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

  // OP-CAP-55 — read-only operations visibility (bounded queries, indexed by idx_incident_record_tenant_status
  // / idx_incident_record_tenant_created). Counts and bounded timeline windows only — never an unbounded scan.
  long countByTenantIdAndStatus(UUID tenantId, IncidentStatus status);

  long countByTenantIdAndStatusAndSeverity(UUID tenantId, IncidentStatus status, IncidentSeverity severity);

  Optional<IncidentRecord> findFirstByTenantIdOrderByUpdatedAtDesc(UUID tenantId);

  List<IncidentRecord> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);
}
