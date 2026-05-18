package com.orderpilot.domain.extraction;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface ExtractedLineItemRepository extends JpaRepository<ExtractedLineItem, UUID> {
  List<ExtractedLineItem> findByTenantIdAndExtractionResultId(UUID tenantId, UUID extractionResultId);
  Optional<ExtractedLineItem> findByIdAndTenantId(UUID id, UUID tenantId);
}