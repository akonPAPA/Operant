package com.orderpilot.domain.validation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalRequirementRepository extends JpaRepository<ApprovalRequirement, UUID> {
  List<ApprovalRequirement> findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(UUID tenantId, UUID validationRunId);
  // OP-CAP-15G: batch-load approvals for several runs (review-origin draft queue lineage) — avoids N+1.
  List<ApprovalRequirement> findByTenantIdAndValidationRunIdInOrderByCreatedAtAsc(UUID tenantId, Collection<UUID> validationRunIds);
  Optional<ApprovalRequirement> findByIdAndTenantId(UUID id, UUID tenantId);
}
