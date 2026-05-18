package com.orderpilot.domain.imports;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> {
  List<ImportJob> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
  Optional<ImportJob> findByIdAndTenantId(UUID id, UUID tenantId);
}