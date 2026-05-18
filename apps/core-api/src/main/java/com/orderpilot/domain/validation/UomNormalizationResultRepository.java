package com.orderpilot.domain.validation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UomNormalizationResultRepository extends JpaRepository<UomNormalizationResult, UUID> {
  List<UomNormalizationResult> findByTenantIdAndValidationRunId(UUID tenantId, UUID validationRunId);
}
