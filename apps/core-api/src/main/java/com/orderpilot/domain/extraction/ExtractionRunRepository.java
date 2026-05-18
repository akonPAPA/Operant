package com.orderpilot.domain.extraction;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface ExtractionRunRepository extends JpaRepository<ExtractionRun, UUID> {
  List<ExtractionRun> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
  Optional<ExtractionRun> findByIdAndTenantId(UUID id, UUID tenantId);
  List<ExtractionRun> findByTenantIdAndSourceTypeAndSourceId(UUID tenantId, String sourceType, UUID sourceId);
}