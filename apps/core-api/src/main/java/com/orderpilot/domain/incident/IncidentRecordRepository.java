package com.orderpilot.domain.incident;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-53 — tenant-scoped storage/listing for incident records.
 */
public interface IncidentRecordRepository extends JpaRepository<IncidentRecord, UUID> {
  /** Tenant-scoped lookup: an incident can only be found (and acted on) within its own tenant. */
  Optional<IncidentRecord> findByIdAndTenantId(UUID id, UUID tenantId);

  List<IncidentRecord> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
