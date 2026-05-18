package com.orderpilot.domain.validation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceCheckResultRepository extends JpaRepository<PriceCheckResult, UUID> {
  List<PriceCheckResult> findByTenantIdAndValidationRunId(UUID tenantId, UUID validationRunId);
}
