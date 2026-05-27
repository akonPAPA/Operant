package com.orderpilot.domain.extraction;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface ExtractionResultRepository extends JpaRepository<ExtractionResult, UUID> {
  List<ExtractionResult> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
  Optional<ExtractionResult> findByIdAndTenantId(UUID id, UUID tenantId);
  Optional<ExtractionResult> findFirstByTenantIdAndExtractionRunId(UUID tenantId, UUID extractionRunId);
  List<ExtractionResult> findByTenantIdAndSourceTypeAndSourceIdOrderByCreatedAtDesc(UUID tenantId, String sourceType, UUID sourceId);
}
