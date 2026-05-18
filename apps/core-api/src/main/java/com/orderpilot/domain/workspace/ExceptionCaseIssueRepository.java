package com.orderpilot.domain.workspace;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface ExceptionCaseIssueRepository extends JpaRepository<ExceptionCaseIssue, UUID> {
  List<ExceptionCaseIssue> findByTenantIdAndExceptionCaseId(UUID tenantId, UUID exceptionCaseId); List<ExceptionCaseIssue> findByTenantIdAndStatus(UUID tenantId, String status);
}
