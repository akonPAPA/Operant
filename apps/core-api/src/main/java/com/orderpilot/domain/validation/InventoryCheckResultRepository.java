package com.orderpilot.domain.validation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryCheckResultRepository extends JpaRepository<InventoryCheckResult, UUID> {
  List<InventoryCheckResult> findByTenantIdAndValidationRunId(UUID tenantId, UUID validationRunId);
}
