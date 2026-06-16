package com.orderpilot.domain.validation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiExtractionValidationRepository extends JpaRepository<AiExtractionValidation, UUID> {
  // Idempotency: at most one AI-advisory validation per extraction result per tenant.
  Optional<AiExtractionValidation> findByTenantIdAndExtractionResultId(UUID tenantId, UUID extractionResultId);
  Optional<AiExtractionValidation> findByIdAndTenantId(UUID id, UUID tenantId);
}
