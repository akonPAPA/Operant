package com.orderpilot.application.services.workspace;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.validation.*;
import com.orderpilot.domain.workspace.*;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExceptionCaseService {
  private final ValidationRunRepository runRepository; private final ValidationIssueRepository issueRepository; private final ApprovalRequirementRepository approvalRepository; private final CustomerMatchResultRepository customerMatchRepository; private final ExceptionCaseRepository caseRepository; private final ExceptionCaseIssueRepository caseIssueRepository; private final OperatorActionService actionService; private final Clock clock;
  public ExceptionCaseService(ValidationRunRepository runRepository, ValidationIssueRepository issueRepository, ApprovalRequirementRepository approvalRepository, CustomerMatchResultRepository customerMatchRepository, ExceptionCaseRepository caseRepository, ExceptionCaseIssueRepository caseIssueRepository, OperatorActionService actionService, Clock clock){this.runRepository=runRepository;this.issueRepository=issueRepository;this.approvalRepository=approvalRepository;this.customerMatchRepository=customerMatchRepository;this.caseRepository=caseRepository;this.caseIssueRepository=caseIssueRepository;this.actionService=actionService;this.clock=clock;}
  @Transactional
  public ExceptionCase createFromValidation(UUID validationRunId) {
    UUID tenantId = TenantContext.requireTenantId();
    ValidationRun run = runRepository.findByIdAndTenantId(validationRunId, tenantId).orElseThrow();
    List<ValidationIssue> issues = issueRepository.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantId, validationRunId).stream().filter(i -> "OPEN".equals(i.getStatus())).toList();
    String severity = severity(issues); String priority = priority(severity);
    boolean waitingApproval = !approvalRepository.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantId, validationRunId).stream().filter(a -> "OPEN".equals(a.getStatus())).toList().isEmpty();
    UUID customerId = customerMatchRepository.findFirstByTenantIdAndValidationRunId(tenantId, validationRunId).map(CustomerMatchResult::getMatchedCustomerAccountId).orElse(null);
    ExceptionCase c = caseRepository.save(new ExceptionCase(tenantId, "CASE-" + clock.instant().toEpochMilli(), "VALIDATION_RUN", validationRunId, run.getExtractionResultId(), validationRunId, customerId, "Validation review for " + run.getExtractionResultId(), waitingApproval ? "WAITING_APPROVAL" : "OPEN", priority, severity, issues.size() + " open validation issue(s)", clock.instant()));
    issues.forEach(i -> caseIssueRepository.save(new ExceptionCaseIssue(tenantId, c.getId(), i.getId(), i.getIssueType(), i.getSeverity(), "OPEN", i.getMessage(), clock.instant())));
    actionService.record(null, "EXCEPTION_CASE", c.getId(), "CASE_OPENED", "Exception case created from validation run", "{\"validationRunId\":\"" + validationRunId + "\"}");
    return c;
  }
  @Transactional(readOnly = true) public List<ExceptionCase> list(){return caseRepository.findByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId());}
  @Transactional(readOnly = true) public ExceptionCase get(UUID id){return caseRepository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow();}
  @Transactional(readOnly = true) public List<ExceptionCaseIssue> issues(UUID id){return caseIssueRepository.findByTenantIdAndExceptionCaseId(TenantContext.requireTenantId(), id);}
  @Transactional public ExceptionCase assign(UUID id, UUID userId){ExceptionCase c=get(id); c.assign(userId, clock.instant()); actionService.record(userId, "EXCEPTION_CASE", id, "CASE_ASSIGNED", "Case assigned", "{}"); return caseRepository.save(c);}
  @Transactional public ExceptionCase status(UUID id, String status){ExceptionCase c=get(id); c.setStatus(status, clock.instant()); actionService.record(null, "EXCEPTION_CASE", id, "OTHER", "Case status set to " + status, "{}"); return caseRepository.save(c);}
  @Transactional public ExceptionCase resolve(UUID id){ExceptionCase c=get(id); c.setStatus("RESOLVED", clock.instant()); actionService.record(null, "EXCEPTION_CASE", id, "ISSUE_RESOLVED", "Exception case resolved", "{}"); return caseRepository.save(c);}
  @Transactional public ExceptionCase reject(UUID id){ExceptionCase c=get(id); c.setStatus("REJECTED", clock.instant()); actionService.record(null, "EXCEPTION_CASE", id, "OTHER", "Exception case rejected", "{}"); return caseRepository.save(c);}
  @Transactional public ExceptionCase cancel(UUID id){ExceptionCase c=get(id); c.setStatus("CANCELLED", clock.instant()); actionService.record(null, "EXCEPTION_CASE", id, "OTHER", "Exception case cancelled", "{}"); return caseRepository.save(c);}
  private String severity(List<ValidationIssue> issues){if(issues.stream().anyMatch(i -> "CRITICAL".equals(i.getSeverity())))return "CRITICAL"; if(issues.stream().anyMatch(i -> "ERROR".equals(i.getSeverity())))return "ERROR"; if(issues.stream().anyMatch(i -> "WARNING".equals(i.getSeverity())))return "WARNING"; return "INFO";}
  private String priority(String severity){return "CRITICAL".equals(severity)?"URGENT":"ERROR".equals(severity)?"HIGH":"WARNING".equals(severity)?"NORMAL":"LOW";}
}
