package com.orderpilot.domain.extraction;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface ExtractedFieldRepository extends JpaRepository<ExtractedField, UUID> {
  List<ExtractedField> findByTenantIdAndExtractionResultId(UUID tenantId, UUID extractionResultId);
  Optional<ExtractedField> findByIdAndTenantId(UUID id, UUID tenantId);
}