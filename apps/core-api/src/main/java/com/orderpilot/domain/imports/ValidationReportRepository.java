package com.orderpilot.domain.imports;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ValidationReportRepository extends JpaRepository<ValidationReport, UUID> {
  Optional<ValidationReport> findByTenantIdAndImportJobId(UUID tenantId, UUID importJobId);
}