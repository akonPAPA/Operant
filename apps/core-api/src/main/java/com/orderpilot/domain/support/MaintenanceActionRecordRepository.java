package com.orderpilot.domain.support;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-51 — tenant-scoped storage/listing for maintenance/update audit records.
 */
public interface MaintenanceActionRecordRepository extends JpaRepository<MaintenanceActionRecord, UUID> {
  List<MaintenanceActionRecord> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
