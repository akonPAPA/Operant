package com.orderpilot.domain.trust.evaluation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-19 Layer C — Projected Memory Evaluation Harness. Tenant-scoped, bounded finders only.
 */
public interface AiMemoryEvaluationRunRepository extends JpaRepository<AiMemoryEvaluationRun, UUID> {
  Optional<AiMemoryEvaluationRun> findByIdAndTenantId(UUID id, UUID tenantId);

  List<AiMemoryEvaluationRun> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

  List<AiMemoryEvaluationRun> findByTenantIdAndRunTypeOrderByCreatedAtDesc(
      UUID tenantId, AiMemoryEvaluationRunType runType, Pageable pageable);
}
