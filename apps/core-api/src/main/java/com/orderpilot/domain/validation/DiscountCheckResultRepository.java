package com.orderpilot.domain.validation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiscountCheckResultRepository extends JpaRepository<DiscountCheckResult, UUID> {
  List<DiscountCheckResult> findByTenantId(UUID tenantId);
  List<DiscountCheckResult> findByTenantIdAndValidationRunId(UUID tenantId, UUID validationRunId);
}
