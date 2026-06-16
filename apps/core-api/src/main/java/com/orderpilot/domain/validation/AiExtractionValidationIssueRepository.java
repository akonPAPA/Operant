package com.orderpilot.domain.validation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiExtractionValidationIssueRepository extends JpaRepository<AiExtractionValidationIssue, UUID> {
  List<AiExtractionValidationIssue> findByTenantIdAndAiExtractionValidationIdOrderByCreatedAtAsc(UUID tenantId, UUID aiExtractionValidationId);
  // Idempotent re-validation clears prior issues before recomputing, so no duplicate rows accrue.
  void deleteByTenantIdAndAiExtractionValidationId(UUID tenantId, UUID aiExtractionValidationId);
}
