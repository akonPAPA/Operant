package com.orderpilot.domain.usage;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsageEventRepository extends JpaRepository<UsageEvent, UUID> {

  /** Idempotent recording: a repeated tenant-scoped key resolves the already-recorded event. */
  Optional<UsageEvent> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

  long countByTenantId(UUID tenantId);

  long countByTenantIdAndMetricType(UUID tenantId, UsageMetricType metricType);
}
