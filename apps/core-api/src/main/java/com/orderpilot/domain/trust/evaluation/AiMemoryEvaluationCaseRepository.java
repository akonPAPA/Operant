package com.orderpilot.domain.trust.evaluation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-19 Layer C — Projected Memory Evaluation Harness. Tenant-scoped, bounded finders only.
 */
public interface AiMemoryEvaluationCaseRepository extends JpaRepository<AiMemoryEvaluationCase, UUID> {
  Optional<AiMemoryEvaluationCase> findByIdAndTenantId(UUID id, UUID tenantId);

  List<AiMemoryEvaluationCase> findByTenantIdAndRunIdOrderByCreatedAtAsc(UUID tenantId, UUID runId);

  List<AiMemoryEvaluationCase> findByTenantIdAndRunIdOrderByCreatedAtAsc(
      UUID tenantId, UUID runId, Pageable pageable);

  long countByTenantIdAndRunId(UUID tenantId, UUID runId);
}
