package com.orderpilot.domain.trust.evaluation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-19 Layer C — Projected Memory Evaluation Harness. Tenant-scoped, bounded finders only.
 */
public interface AiMemoryEvaluationResultRepository extends JpaRepository<AiMemoryEvaluationResult, UUID> {
  List<AiMemoryEvaluationResult> findByTenantIdAndRunIdOrderByCreatedAtAsc(
      UUID tenantId, UUID runId, Pageable pageable);
}
