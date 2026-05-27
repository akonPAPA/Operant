package com.orderpilot.domain.validation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarginCheckResultRepository extends JpaRepository<MarginCheckResult, UUID> {
  List<MarginCheckResult> findByTenantId(UUID tenantId);
  List<MarginCheckResult> findByTenantIdAndValidationRunId(UUID tenantId, UUID validationRunId);
}
