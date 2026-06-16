package com.orderpilot.domain.validation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ValidationIssueRepository extends JpaRepository<ValidationIssue, UUID> {
  List<ValidationIssue> findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(UUID tenantId, UUID validationRunId);
  // OP-CAP-15G: batch-load issues for several runs (review-origin draft queue lineage) — avoids N+1.
  List<ValidationIssue> findByTenantIdAndValidationRunIdInOrderByCreatedAtAsc(UUID tenantId, Collection<UUID> validationRunIds);
  List<ValidationIssue> findByTenantIdAndExtractionResultIdInOrderByCreatedAtAsc(UUID tenantId, List<UUID> extractionResultIds);
  List<ValidationIssue> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, String status);
  Optional<ValidationIssue> findByIdAndTenantId(UUID id, UUID tenantId);
}
