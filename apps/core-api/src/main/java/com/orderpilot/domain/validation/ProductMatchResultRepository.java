package com.orderpilot.domain.validation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductMatchResultRepository extends JpaRepository<ProductMatchResult, UUID> {
  List<ProductMatchResult> findByTenantIdAndValidationRunId(UUID tenantId, UUID validationRunId);
  List<ProductMatchResult> findByTenantIdAndValidationRunIdAndStatus(UUID tenantId, UUID validationRunId, String status);
  Optional<ProductMatchResult> findFirstByTenantIdAndValidationRunIdAndExtractedLineItemId(UUID tenantId, UUID validationRunId, UUID extractedLineItemId);
}
