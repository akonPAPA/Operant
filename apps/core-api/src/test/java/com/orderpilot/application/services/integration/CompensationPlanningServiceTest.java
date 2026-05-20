package com.orderpilot.application.services.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.integration.*;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CompensationPlanningService.class, ConnectorIdempotencyService.class, ChangeRequestService.class, AuditEventService.class, CoreConfiguration.class})
class CompensationPlanningServiceTest {
  @Autowired private CompensationPlanningService compensationPlanningService;
  @Autowired private ConnectorIdempotencyService connectorIdempotencyService;
  @Autowired private ChangeRequestService changeRequestService;
  @Autowired private CompensationPlanRepository compensationPlanRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private ConnectorCommandRepository connectorCommandRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void noCompensationNeededWhenExternalWriteWasNeverExecuted() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ConnectorCommand command = connectorCommand();

    CompensationPlan plan = compensationPlanningService.planForConnectorCommand(command.getId(), ConnectorCommandExecutionState.EXECUTION_BLOCKED, CompensationReasonCode.EXTERNAL_WRITE_NOT_EXECUTED, "blocked before execution", null);

    assertThat(plan.getTenantId()).isEqualTo(tenantId);
    assertThat(plan.getCompensationActionType()).isEqualTo(CompensationActionType.NOOP);
    assertThat(plan.getStatus()).isEqualTo(CompensationPlanStatus.NOT_REQUIRED);
    assertThat(plan.isRequiresHumanApproval()).isFalse();
    assertThat(plan.isSafeToAutoExecute()).isFalse();
  }

  @Test
  void manualReviewRequiredWhenExternalWriteStateIsUnknown() {
    TenantContext.setTenantId(UUID.randomUUID());
    ConnectorCommand command = connectorCommand();

    CompensationPlan plan = compensationPlanningService.planForConnectorCommand(command.getId(), ConnectorCommandExecutionState.MANUAL_REVIEW_REQUIRED, CompensationReasonCode.EXTERNAL_TIMEOUT_UNKNOWN_STATE, "timeout with unknown external state", null);

    assertThat(plan.getCompensationActionType()).isEqualTo(CompensationActionType.CREATE_MANUAL_REVIEW_TASK);
    assertThat(plan.getReasonCode()).isEqualTo(CompensationReasonCode.EXTERNAL_TIMEOUT_UNKNOWN_STATE);
    assertThat(plan.getStatus()).isEqualTo(CompensationPlanStatus.MANUAL_REVIEW_REQUIRED);
    assertThat(plan.isRequiresHumanApproval()).isTrue();
    assertThat(plan.isSafeToAutoExecute()).isFalse();
  }

  @Test
  void compensationRequiredWhenCommandWasPartiallyExecuted() {
    TenantContext.setTenantId(UUID.randomUUID());
    ConnectorCommand command = connectorCommand();

    CompensationPlan plan = compensationPlanningService.planForConnectorCommand(command.getId(), ConnectorCommandExecutionState.COMPENSATION_REQUIRED, CompensationReasonCode.EXTERNAL_WRITE_PARTIALLY_EXECUTED, "external draft may exist", UUID.randomUUID());

    assertThat(plan.getCompensationActionType()).isEqualTo(CompensationActionType.REVERSE_CONNECTOR_COMMAND);
    assertThat(plan.getReasonCode()).isEqualTo(CompensationReasonCode.EXTERNAL_WRITE_PARTIALLY_EXECUTED);
    assertThat(plan.getStatus()).isEqualTo(CompensationPlanStatus.APPROVAL_REQUIRED);
    assertThat(plan.isRequiresHumanApproval()).isTrue();
    assertThat(plan.isSafeToAutoExecute()).isFalse();
  }

  @Test
  void approvalRevokedCreatesSafeDocumentOnlyPlan() {
    TenantContext.setTenantId(UUID.randomUUID());
    ChangeRequest request = approvedChangeRequest();

    CompensationPlan plan = compensationPlanningService.planForApprovalRevoked(request.getId(), UUID.randomUUID());

    assertThat(plan.getSourceChangeRequestId()).isEqualTo(request.getId());
    assertThat(plan.getConnectorCommandId()).isNull();
    assertThat(plan.getCompensationActionType()).isEqualTo(CompensationActionType.DOCUMENT_ONLY);
    assertThat(plan.getReasonCode()).isEqualTo(CompensationReasonCode.APPROVAL_REVOKED);
    assertThat(plan.isSafeToAutoExecute()).isFalse();
  }

  @Test
  void tenantPolicyBlockPreservesTenantAndDoesNotCreateConnectorCommand() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChangeRequest request = approvedChangeRequest();

    CompensationPlan plan = compensationPlanningService.planForTenantPolicyBlock(request.getId(), null);

    assertThat(plan.getTenantId()).isEqualTo(tenantId);
    assertThat(plan.getReasonCode()).isEqualTo(CompensationReasonCode.TENANT_POLICY_BLOCKED);
    assertThat(plan.getCompensationActionType()).isEqualTo(CompensationActionType.DOCUMENT_ONLY);
    assertThat(connectorCommandRepository.count()).isZero();
  }

  @Test
  void failedChangeRequestTransitionUsesNoopBeforeExecution() {
    TenantContext.setTenantId(UUID.randomUUID());
    ChangeRequest request = changeRequestService.createChangeRequest("DEMO_ERP", "DRAFT_QUOTE", "EXPORT", "DRAFT_QUOTE", UUID.randomUUID(), "{}", "failed-transition", null);

    CompensationPlan plan = compensationPlanningService.planForFailedChangeRequestTransition(request.getId(), "validation failed", null);

    assertThat(plan.getCompensationActionType()).isEqualTo(CompensationActionType.NOOP);
    assertThat(plan.getReasonCode()).isEqualTo(CompensationReasonCode.VALIDATION_FAILED_BEFORE_EXECUTION);
    assertThat(plan.getStatus()).isEqualTo(CompensationPlanStatus.NOT_REQUIRED);
    assertThat(plan.isSafeToAutoExecute()).isFalse();
  }

  @Test
  void compensationPlannerOnlyCreatesPlanAndAuditEvent() {
    TenantContext.setTenantId(UUID.randomUUID());
    ConnectorCommand command = connectorCommand();

    CompensationPlan plan = compensationPlanningService.planForConnectorCommand(command.getId(), ConnectorCommandExecutionState.EXECUTION_BLOCKED, CompensationReasonCode.EXTERNAL_WRITE_NOT_EXECUTED, null, null);

    assertThat(compensationPlanRepository.findById(plan.getId())).isPresent();
    assertThat(connectorCommandRepository.findById(command.getId()).orElseThrow().getStatus()).isEqualTo("EXECUTION_DISABLED");
    assertThat(auditEventRepository.findAll()).extracting("action").contains("COMPENSATION_PLAN_CREATED");
  }

  private ConnectorCommand connectorCommand() {
    ChangeRequest request = approvedChangeRequest();
    return connectorIdempotencyService.createCommandFromApprovedChangeRequest(request.getId(), null, "DEMO_ERP", "CREATE_DRAFT_QUOTE", "{}");
  }

  private ChangeRequest approvedChangeRequest() {
    ChangeRequest request = changeRequestService.createChangeRequest("DEMO_ERP", "DRAFT_QUOTE", "EXPORT", "DRAFT_QUOTE", UUID.randomUUID(), "{}", UUID.randomUUID().toString(), null);
    changeRequestService.validateChangeRequest(request.getId());
    return changeRequestService.approveChangeRequest(request.getId(), UUID.randomUUID());
  }
}
