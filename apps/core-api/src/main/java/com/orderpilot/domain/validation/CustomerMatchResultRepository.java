package com.orderpilot.domain.validation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerMatchResultRepository extends JpaRepository<CustomerMatchResult, UUID> {
  List<CustomerMatchResult> findByTenantIdAndValidationRunId(UUID tenantId, UUID validationRunId);
  Optional<CustomerMatchResult> findFirstByTenantIdAndValidationRunId(UUID tenantId, UUID validationRunId);
}
