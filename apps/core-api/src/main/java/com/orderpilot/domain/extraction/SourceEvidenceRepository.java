package com.orderpilot.domain.extraction;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface SourceEvidenceRepository extends JpaRepository<SourceEvidence, UUID> {
  List<SourceEvidence> findByTenantIdAndExtractionRunId(UUID tenantId, UUID extractionRunId);
}