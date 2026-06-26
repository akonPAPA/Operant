package com.orderpilot.domain.support;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-51 — tenant-scoped storage/listing for data-repair dry-run requests.
 */
public interface DataRepairRequestRepository extends JpaRepository<DataRepairRequest, UUID> {
  List<DataRepairRequest> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

  /** OP-CAP-52 — tenant-scoped lookup: a request can only be found (and acted on) within its own tenant. */
  Optional<DataRepairRequest> findByIdAndTenantId(UUID id, UUID tenantId);

  // OP-CAP-55 — read-only operations visibility (bounded, tenant-scoped via idx_data_repair_request_tenant).
  long countByTenantIdAndApprovalStatus(UUID tenantId, DataRepairRequest.ApprovalStatus approvalStatus);

  long countByTenantIdAndExecutionStatus(UUID tenantId, DataRepairRequest.ExecutionStatus executionStatus);

  Optional<DataRepairRequest> findFirstByTenantIdOrderByCreatedAtDesc(UUID tenantId);

  List<DataRepairRequest> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);
}
