package com.orderpilot.application.services.integration;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.integration.*;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompensationPlanningService {
  private final CompensationPlanRepository compensationPlanRepository;
  private final ConnectorCommandRepository connectorCommandRepository;
  private final ChangeRequestRepository changeRequestRepository;
  private final AuditEventService auditEventService;
  private final Clock clock;

  public CompensationPlanningService(CompensationPlanRepository compensationPlanRepository, ConnectorCommandRepository connectorCommandRepository, ChangeRequestRepository changeRequestRepository, AuditEventService auditEventService, Clock clock) {
    this.compensationPlanRepository = compensationPlanRepository;
    this.connectorCommandRepository = connectorCommandRepository;
    this.changeRequestRepository = changeRequestRepository;
    this.auditEventService = auditEventService;
    this.clock = clock;
  }

  @Transactional
  public CompensationPlan planForConnectorCommand(UUID connectorCommandId, ConnectorCommandExecutionState executionState, CompensationReasonCode reasonCode, String humanReadableReason, UUID createdBy) {
    UUID tenantId = TenantContext.requireTenantId();
    ConnectorCommand command = connectorCommandRepository.findByIdAndTenantId(connectorCommandId, tenantId)
        .orElseThrow(() -> new NotFoundException("Connector command not found: " + connectorCommandId));
    CompensationDecision decision = decide(executionState, reasonCode);
    CompensationPlan plan = compensationPlanRepository.save(new CompensationPlan(
        tenantId,
        command.getChangeRequestId(),
        command.getId(),
        command.getConnectorType(),
        null,
        decision.actionType(),
        decision.reasonCode(),
        humanReadableReason == null || humanReadableReason.isBlank() ? defaultReason(decision.reasonCode()) : humanReadableReason,
        decision.requiresHumanApproval(),
        decision.status(),
        clock.instant(),
        createdBy,
        UUID.randomUUID()));
    auditEventService.record("COMPENSATION_PLAN_CREATED", "COMPENSATION_PLAN", plan.getId().toString(), createdBy, "{\"safeToAutoExecute\":false,\"externalExecution\":\"DISABLED\"}");
    return plan;
  }

  @Transactional
  public CompensationPlan planForApprovalRevoked(UUID changeRequestId, UUID createdBy) {
    ChangeRequest changeRequest = getChangeRequest(changeRequestId);
    return createChangeRequestPlan(changeRequest, CompensationActionType.DOCUMENT_ONLY, CompensationReasonCode.APPROVAL_REVOKED, "Approval was revoked before external execution. Document only; no external rollback is required.", false, CompensationPlanStatus.NOT_REQUIRED, createdBy);
  }

  @Transactional
  public CompensationPlan planForTenantPolicyBlock(UUID changeRequestId, UUID createdBy) {
    ChangeRequest changeRequest = getChangeRequest(changeRequestId);
    return createChangeRequestPlan(changeRequest, CompensationActionType.DOCUMENT_ONLY, CompensationReasonCode.TENANT_POLICY_BLOCKED, "Tenant policy blocked external execution before any provider write.", false, CompensationPlanStatus.NOT_REQUIRED, createdBy);
  }

  @Transactional
  public CompensationPlan planForFailedChangeRequestTransition(UUID changeRequestId, String reason, UUID createdBy) {
    ChangeRequest changeRequest = getChangeRequest(changeRequestId);
    return createChangeRequestPlan(changeRequest, CompensationActionType.NOOP, CompensationReasonCode.VALIDATION_FAILED_BEFORE_EXECUTION, reason == null || reason.isBlank() ? "Validation failed before connector execution." : reason, false, CompensationPlanStatus.NOT_REQUIRED, createdBy);
  }

  @Transactional(readOnly = true)
  public List<CompensationPlan> listPlans() {
    return compensationPlanRepository.findByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId());
  }

  @Transactional(readOnly = true)
  public CompensationPlan getPlan(UUID id) {
    return compensationPlanRepository.findByIdAndTenantId(id, TenantContext.requireTenantId())
        .orElseThrow(() -> new NotFoundException("Compensation plan not found: " + id));
  }

  private ChangeRequest getChangeRequest(UUID changeRequestId) {
    return changeRequestRepository.findByIdAndTenantId(changeRequestId, TenantContext.requireTenantId())
        .orElseThrow(() -> new NotFoundException("Change request not found: " + changeRequestId));
  }

  private CompensationPlan createChangeRequestPlan(ChangeRequest changeRequest, CompensationActionType actionType, CompensationReasonCode reasonCode, String reason, boolean requiresHumanApproval, CompensationPlanStatus status, UUID createdBy) {
    CompensationPlan plan = compensationPlanRepository.save(new CompensationPlan(changeRequest.getTenantId(), changeRequest.getId(), null, changeRequest.getTargetSystem(), null, actionType, reasonCode, reason, requiresHumanApproval, status, clock.instant(), createdBy, UUID.randomUUID()));
    auditEventService.record("COMPENSATION_PLAN_CREATED", "COMPENSATION_PLAN", plan.getId().toString(), createdBy, "{\"safeToAutoExecute\":false,\"externalExecution\":\"DISABLED\"}");
    return plan;
  }

  private static CompensationDecision decide(ConnectorCommandExecutionState executionState, CompensationReasonCode reasonCode) {
    if (executionState == ConnectorCommandExecutionState.EXECUTED || executionState == ConnectorCommandExecutionState.COMPENSATION_REQUIRED) {
      return new CompensationDecision(CompensationActionType.REVERSE_CONNECTOR_COMMAND, CompensationReasonCode.EXTERNAL_WRITE_PARTIALLY_EXECUTED, true, CompensationPlanStatus.APPROVAL_REQUIRED);
    }
    if (executionState == ConnectorCommandExecutionState.MANUAL_REVIEW_REQUIRED || reasonCode == CompensationReasonCode.EXTERNAL_TIMEOUT_UNKNOWN_STATE) {
      return new CompensationDecision(CompensationActionType.CREATE_MANUAL_REVIEW_TASK, CompensationReasonCode.EXTERNAL_TIMEOUT_UNKNOWN_STATE, true, CompensationPlanStatus.MANUAL_REVIEW_REQUIRED);
    }
    if (executionState == ConnectorCommandExecutionState.EXECUTION_FAILED) {
      return new CompensationDecision(CompensationActionType.CREATE_MANUAL_REVIEW_TASK, CompensationReasonCode.EXTERNAL_WRITE_FAILED_AFTER_LOCAL_STATE, true, CompensationPlanStatus.MANUAL_REVIEW_REQUIRED);
    }
    CompensationReasonCode safeReason = reasonCode == null ? CompensationReasonCode.EXTERNAL_WRITE_NOT_EXECUTED : reasonCode;
    return new CompensationDecision(CompensationActionType.NOOP, safeReason, false, CompensationPlanStatus.NOT_REQUIRED);
  }

  private static String defaultReason(CompensationReasonCode reasonCode) {
    return switch (reasonCode) {
      case EXTERNAL_WRITE_NOT_EXECUTED -> "External write was not executed; no external rollback is required.";
      case EXTERNAL_TIMEOUT_UNKNOWN_STATE -> "External execution state is unknown; manual review is required.";
      case EXTERNAL_WRITE_PARTIALLY_EXECUTED -> "External write may be partially executed; compensation requires human approval.";
      case EXTERNAL_WRITE_FAILED_AFTER_LOCAL_STATE -> "External write failed after local state changed; manual review is required.";
      case VALIDATION_FAILED_BEFORE_EXECUTION -> "Validation failed before external execution.";
      case APPROVAL_REVOKED -> "Approval was revoked before external execution.";
      case TENANT_POLICY_BLOCKED -> "Tenant policy blocked execution.";
      case MANUAL_REVIEW_REQUESTED -> "Manual review was requested.";
    };
  }

  private record CompensationDecision(CompensationActionType actionType, CompensationReasonCode reasonCode, boolean requiresHumanApproval, CompensationPlanStatus status) {}
}
