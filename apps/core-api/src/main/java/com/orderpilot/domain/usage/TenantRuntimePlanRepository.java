package com.orderpilot.domain.usage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRuntimePlanRepository extends JpaRepository<TenantRuntimePlan, UUID> {

  /**
   * All plans for a tenant, newest effective window first. Tenant-scoped (no cross-tenant read); the
   * policy filters to the currently-active plan deterministically.
   */
  List<TenantRuntimePlan> findByTenantIdOrderByEffectiveFromDesc(UUID tenantId);

  /**
   * OP-CAP-16I: tenant-scoped plan lookup by id. Returns empty when the plan does not exist or
   * belongs to another tenant — never leaks another tenant's plan to the admin command surface.
   */
  Optional<TenantRuntimePlan> findByIdAndTenantId(UUID id, UUID tenantId);
}
