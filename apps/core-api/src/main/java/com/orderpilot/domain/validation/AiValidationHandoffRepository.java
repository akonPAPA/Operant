package com.orderpilot.domain.validation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiValidationHandoffRepository extends JpaRepository<AiValidationHandoff, UUID> {
  // Idempotency: at most one handoff per validation per tenant.
  Optional<AiValidationHandoff> findByTenantIdAndValidationId(UUID tenantId, UUID validationId);
  Optional<AiValidationHandoff> findByIdAndTenantId(UUID id, UUID tenantId);
  List<AiValidationHandoff> findByTenantIdOrderByUpdatedAtDesc(UUID tenantId, Pageable pageable);
  List<AiValidationHandoff> findByTenantIdAndStatusOrderByUpdatedAtDesc(UUID tenantId, String status, Pageable pageable);
}
