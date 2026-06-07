package com.orderpilot.domain.extraction;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface ExtractionRunRepository extends JpaRepository<ExtractionRun, UUID> {
  List<ExtractionRun> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
  Optional<ExtractionRun> findByIdAndTenantId(UUID id, UUID tenantId);
  List<ExtractionRun> findByTenantIdAndSourceTypeAndSourceId(UUID tenantId, String sourceType, UUID sourceId);
  // OP-CAP-07D idempotency: at most one AI-worker advisory run is persisted per processing job.
  Optional<ExtractionRun> findFirstByTenantIdAndProcessingJobIdAndProviderType(UUID tenantId, UUID processingJobId, String providerType);
}