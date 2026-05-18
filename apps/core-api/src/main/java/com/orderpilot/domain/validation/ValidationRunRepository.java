package com.orderpilot.domain.validation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ValidationRunRepository extends JpaRepository<ValidationRun, UUID> {
  List<ValidationRun> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
  List<ValidationRun> findByTenantIdAndExtractionResultIdOrderByCreatedAtDesc(UUID tenantId, UUID extractionResultId);
  Optional<ValidationRun> findByIdAndTenantId(UUID id, UUID tenantId);
}
