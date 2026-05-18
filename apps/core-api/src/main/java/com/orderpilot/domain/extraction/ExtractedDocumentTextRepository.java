package com.orderpilot.domain.extraction;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface ExtractedDocumentTextRepository extends JpaRepository<ExtractedDocumentText, UUID> {
  Optional<ExtractedDocumentText> findFirstByTenantIdAndExtractionRunId(UUID tenantId, UUID extractionRunId);
}