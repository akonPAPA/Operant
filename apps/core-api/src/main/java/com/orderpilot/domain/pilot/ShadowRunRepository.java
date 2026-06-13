package com.orderpilot.domain.pilot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShadowRunRepository extends JpaRepository<ShadowRun, UUID> {
  Optional<ShadowRun> findByIdAndTenantId(UUID id, UUID tenantId);
  long countByTenantId(UUID tenantId);
  List<ShadowRun> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
  List<ShadowRun> findByTenantIdAndSourceTypeOrderByCreatedAtDesc(UUID tenantId, String sourceType);
  List<ShadowRun> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, String status);
  List<ShadowRun> findByTenantIdAndSourceTypeAndStatusOrderByCreatedAtDesc(UUID tenantId, String sourceType, String status);
}
