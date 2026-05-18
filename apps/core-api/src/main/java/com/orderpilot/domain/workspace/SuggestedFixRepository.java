package com.orderpilot.domain.workspace;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface SuggestedFixRepository extends JpaRepository<SuggestedFix, UUID> {
  List<SuggestedFix> findByTenantIdAndValidationRunId(UUID tenantId, UUID validationRunId); List<SuggestedFix> findByTenantIdAndExceptionCaseId(UUID tenantId, UUID exceptionCaseId); Optional<SuggestedFix> findByIdAndTenantId(UUID id, UUID tenantId);
}
