package com.orderpilot.domain.trust.analytics;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-17E Trust Analytics Read Models. Tenant-scoped lookups; the distribution is unique per
 * (tenant, period).
 */
public interface TrustRiskDistributionViewRepository extends JpaRepository<TrustRiskDistributionView, UUID> {
  Optional<TrustRiskDistributionView> findByTenantIdAndPeriodKey(UUID tenantId, String periodKey);

  List<TrustRiskDistributionView> findByTenantIdAndPeriodKeyBetweenOrderByPeriodKeyAsc(
      UUID tenantId, String fromPeriodKey, String toPeriodKey, Pageable pageable);
}
