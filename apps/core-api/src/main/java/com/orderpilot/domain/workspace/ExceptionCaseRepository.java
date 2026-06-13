package com.orderpilot.domain.workspace;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface ExceptionCaseRepository extends JpaRepository<ExceptionCase, UUID> {
  List<ExceptionCase> findByTenantIdOrderByCreatedAtDesc(UUID tenantId); Optional<ExceptionCase> findByIdAndTenantId(UUID id, UUID tenantId); Optional<ExceptionCase> findFirstByTenantIdAndValidationRunIdOrderByCreatedAtDesc(UUID tenantId, UUID validationRunId); List<ExceptionCase> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, String status); long countByTenantIdAndStatus(UUID tenantId, String status); long countByTenantId(UUID tenantId);
  Optional<ExceptionCase> findFirstByTenantIdAndSourceTypeAndSourceIdOrderByCreatedAtDesc(UUID tenantId, String sourceType, UUID sourceId);
}
