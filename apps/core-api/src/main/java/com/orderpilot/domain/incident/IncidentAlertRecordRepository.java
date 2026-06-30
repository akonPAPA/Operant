package com.orderpilot.domain.incident;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-53 — storage/listing for record-only incident alerts.
 */
public interface IncidentAlertRecordRepository extends JpaRepository<IncidentAlertRecord, UUID> {
  /**
   * Tenant-and-incident-scoped lookup. Incident ids are navigation handles, never tenant authority.
   */
  List<IncidentAlertRecord> findByTenantIdAndIncidentIdOrderByCreatedAtDesc(
      UUID tenantId, UUID incidentId);
}
