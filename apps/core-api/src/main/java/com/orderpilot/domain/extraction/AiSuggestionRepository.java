package com.orderpilot.domain.extraction;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface AiSuggestionRepository extends JpaRepository<AiSuggestion, UUID> {
  List<AiSuggestion> findByTenantIdAndExtractionRunId(UUID tenantId, UUID extractionRunId);
}