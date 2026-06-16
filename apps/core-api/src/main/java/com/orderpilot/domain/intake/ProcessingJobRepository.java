package com.orderpilot.domain.intake;
import java.util.*; import org.springframework.data.domain.Pageable; import org.springframework.data.jpa.repository.JpaRepository;
public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, UUID> {
  List<ProcessingJob> findByTenantIdOrderByQueuedAtDesc(UUID tenantId);
  // OP-CAP-28: bounded, tenant-scoped, most-recent-first page for the safe status/list contract (no full scan).
  List<ProcessingJob> findByTenantIdOrderByQueuedAtDesc(UUID tenantId, Pageable pageable);
  Optional<ProcessingJob> findByIdAndTenantId(UUID id, UUID tenantId);
  Optional<ProcessingJob> findFirstByTenantIdAndTargetTypeAndTargetIdAndStatus(UUID tenantId, String targetType, UUID targetId, String status);
  // OP-CAP-21: bounded counts + most-recent-job lookup for the Command Center runtime health summary.
  long countByTenantIdAndStatus(UUID tenantId, String status);
  Optional<ProcessingJob> findFirstByTenantIdOrderByQueuedAtDesc(UUID tenantId);
}