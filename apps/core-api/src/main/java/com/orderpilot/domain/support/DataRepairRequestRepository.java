package com.orderpilot.domain.support;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-51 — tenant-scoped storage/listing for data-repair dry-run requests.
 */
public interface DataRepairRequestRepository extends JpaRepository<DataRepairRequest, UUID> {
  List<DataRepairRequest> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
