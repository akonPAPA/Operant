package com.orderpilot.domain.trust.analytics;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-17E Trust Analytics Read Models. Tenant-scoped lookups; the dashboard is unique per
 * (tenant, counterparty).
 */
public interface CounterpartyTrustDashboardViewRepository
    extends JpaRepository<CounterpartyTrustDashboardView, UUID> {
  Optional<CounterpartyTrustDashboardView> findByTenantIdAndCounterpartyId(UUID tenantId, UUID counterpartyId);
}
