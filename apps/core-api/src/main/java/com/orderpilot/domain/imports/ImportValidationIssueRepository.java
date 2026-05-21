package com.orderpilot.domain.imports;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportValidationIssueRepository extends JpaRepository<ImportValidationIssue, UUID> {
  List<ImportValidationIssue> findByTenantIdAndImportJobIdOrderByRowNumber(UUID tenantId, UUID importJobId);
  void deleteByTenantIdAndImportJobId(UUID tenantId, UUID importJobId);
}