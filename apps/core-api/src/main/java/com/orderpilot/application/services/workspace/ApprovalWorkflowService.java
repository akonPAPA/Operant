package com.orderpilot.application.services.workspace;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.validation.ApprovalRequirementRepository;
import com.orderpilot.domain.workspace.*;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalWorkflowService {
  private final ApprovalDecisionRepository repository; private final ApprovalRequirementRepository requirementRepository; private final OperatorActionService actionService; private final Clock clock;
  public ApprovalWorkflowService(ApprovalDecisionRepository repository, ApprovalRequirementRepository requirementRepository, OperatorActionService actionService, Clock clock){this.repository=repository;this.requirementRepository=requirementRepository;this.actionService=actionService;this.clock=clock;}
  @Transactional
  public ApprovalDecision decide(String targetType, UUID targetId, String decision, String reason, UUID decidedBy) {
    ApprovalDecision record = repository.save(new ApprovalDecision(TenantContext.requireTenantId(), targetType, targetId, decision, reason, decidedBy, clock.instant()));
    if ("APPROVAL_REQUIREMENT".equals(targetType)) requirementRepository.findByIdAndTenantId(targetId, TenantContext.requireTenantId()).ifPresent(r -> { r.setStatus("APPROVED".equals(decision) ? "APPROVED" : "REJECTED".equals(decision) ? "REJECTED" : "CANCELLED", clock.instant()); requirementRepository.save(r); });
    actionService.record(decidedBy, targetType, targetId, "APPROVAL_DECIDED", "Internal workflow approval decision recorded: " + decision, "{\"decision\":\"" + decision + "\"}");
    return record;
  }
  @Transactional(readOnly = true) public List<ApprovalDecision> forTarget(String targetType, UUID targetId){return repository.findByTenantIdAndTargetTypeAndTargetIdOrderByCreatedAtDesc(TenantContext.requireTenantId(), targetType, targetId);}
}
