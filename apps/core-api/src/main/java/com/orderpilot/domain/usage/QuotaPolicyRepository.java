package com.orderpilot.domain.usage;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuotaPolicyRepository extends JpaRepository<QuotaPolicy, UUID> {

  /** Tenant-scoped policy lookup for a metric. Tenant isolation: never resolves another tenant. */
  Optional<QuotaPolicy> findByTenantIdAndMetricType(UUID tenantId, UsageMetricType metricType);
}
