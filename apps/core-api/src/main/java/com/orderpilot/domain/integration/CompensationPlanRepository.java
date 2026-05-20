package com.orderpilot.domain.integration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompensationPlanRepository extends JpaRepository<CompensationPlan, UUID> {
  Optional<CompensationPlan> findByIdAndTenantId(UUID id, UUID tenantId);
  List<CompensationPlan> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
  List<CompensationPlan> findByTenantIdAndConnectorCommandIdOrderByCreatedAtDesc(UUID tenantId, UUID connectorCommandId);
  List<CompensationPlan> findByTenantIdAndSourceChangeRequestIdOrderByCreatedAtDesc(UUID tenantId, UUID sourceChangeRequestId);
}
