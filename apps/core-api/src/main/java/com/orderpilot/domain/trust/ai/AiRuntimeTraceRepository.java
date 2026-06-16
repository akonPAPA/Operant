package com.orderpilot.domain.trust.ai;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-17F AI Data Runtime / Tenant-Scoped AI Memory Governance.
 *
 * Tenant-scoped, bounded queries only. The caller always supplies a clamped {@link Pageable}; every
 * finder is tenant-isolated.
 */
public interface AiRuntimeTraceRepository extends JpaRepository<AiRuntimeTrace, UUID> {
  Optional<AiRuntimeTrace> findByIdAndTenantId(UUID id, UUID tenantId);

  List<AiRuntimeTrace> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

  List<AiRuntimeTrace> findByTenantIdAndWorkloadTypeOrderByCreatedAtDesc(
      UUID tenantId, String workloadType, Pageable pageable);

  List<AiRuntimeTrace> findByTenantIdAndStatusOrderByCreatedAtDesc(
      UUID tenantId, AiRuntimeStatus status, Pageable pageable);

  List<AiRuntimeTrace> findByTenantIdAndSourceTypeAndSourceIdOrderByCreatedAtDesc(
      UUID tenantId, AiMemorySourceType sourceType, UUID sourceId, Pageable pageable);
}
