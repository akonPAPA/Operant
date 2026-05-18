package com.orderpilot.application.services.workspace;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.validation.ValidationIssueRepository;
import com.orderpilot.domain.workspace.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceSummaryService {
  private final ExceptionCaseRepository caseRepository; private final DraftQuoteRepository quoteRepository; private final DraftOrderRepository orderRepository; private final ValidationIssueRepository issueRepository; private final OperatorActionService actionService;
  public WorkspaceSummaryService(ExceptionCaseRepository caseRepository, DraftQuoteRepository quoteRepository, DraftOrderRepository orderRepository, ValidationIssueRepository issueRepository, OperatorActionService actionService){this.caseRepository=caseRepository;this.quoteRepository=quoteRepository;this.orderRepository=orderRepository;this.issueRepository=issueRepository;this.actionService=actionService;}
  @Transactional(readOnly = true)
  public WorkspaceSummary summary() {
    var tenantId = TenantContext.requireTenantId();
    long openCases = caseRepository.countByTenantIdAndStatus(tenantId, "OPEN") + caseRepository.countByTenantIdAndStatus(tenantId, "IN_REVIEW");
    long waitingApproval = caseRepository.countByTenantIdAndStatus(tenantId, "WAITING_APPROVAL") + quoteRepository.countByTenantIdAndStatus(tenantId, "WAITING_APPROVAL") + orderRepository.countByTenantIdAndStatus(tenantId, "WAITING_APPROVAL");
    long quoteReview = quoteRepository.countByTenantIdAndStatus(tenantId, "NEEDS_REVIEW");
    long orderReview = orderRepository.countByTenantIdAndStatus(tenantId, "NEEDS_REVIEW");
    long highSeverity = issueRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, "OPEN").stream().filter(i -> "ERROR".equals(i.getSeverity()) || "CRITICAL".equals(i.getSeverity())).count();
    return new WorkspaceSummary(openCases, waitingApproval, quoteReview, orderReview, highSeverity, actionService.recent());
  }
  public record WorkspaceSummary(long openExceptionCases, long waitingApproval, long draftQuotesNeedingReview, long draftOrdersNeedingReview, long highSeverityIssues, List<OperatorAction> recentActions) {}
}
