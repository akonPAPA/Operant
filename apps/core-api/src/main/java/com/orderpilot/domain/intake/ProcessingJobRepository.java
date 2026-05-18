package com.orderpilot.domain.intake;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, UUID> {
  List<ProcessingJob> findByTenantIdOrderByQueuedAtDesc(UUID tenantId);
  Optional<ProcessingJob> findByIdAndTenantId(UUID id, UUID tenantId);
  Optional<ProcessingJob> findFirstByTenantIdAndTargetTypeAndTargetIdAndStatus(UUID tenantId, String targetType, UUID targetId, String status);
}