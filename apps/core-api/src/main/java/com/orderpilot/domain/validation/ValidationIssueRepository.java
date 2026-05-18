package com.orderpilot.domain.validation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ValidationIssueRepository extends JpaRepository<ValidationIssue, UUID> {
  List<ValidationIssue> findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(UUID tenantId, UUID validationRunId);
  List<ValidationIssue> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, String status);
  Optional<ValidationIssue> findByIdAndTenantId(UUID id, UUID tenantId);
}
