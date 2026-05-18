package com.orderpilot.domain.imports;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportStagingRowRepository extends JpaRepository<ImportStagingRow, UUID> {
  List<ImportStagingRow> findByTenantIdAndImportJobIdOrderByRowNumber(UUID tenantId, UUID importJobId);
  long countByTenantIdAndImportJobId(UUID tenantId, UUID importJobId);
}