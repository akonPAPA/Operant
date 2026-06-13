package com.orderpilot.domain.usage;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeatureEntitlementRepository extends JpaRepository<FeatureEntitlement, UUID> {

  /**
   * Entitlement rows for a tenant's plan + feature. Tenant- and plan-scoped (no cross-tenant read);
   * the policy filters to the currently-effective row deterministically. Indexed by
   * {@code (tenant_id, plan_id, feature_type)}.
   */
  List<FeatureEntitlement> findByTenantIdAndPlanIdAndFeatureType(
      UUID tenantId, UUID planId, String featureType);
}
