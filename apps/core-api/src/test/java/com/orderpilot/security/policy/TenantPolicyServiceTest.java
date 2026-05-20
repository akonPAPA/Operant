package com.orderpilot.security.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.domain.integration.ConnectorCommandExecutionState;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantPolicyServiceTest {
  private static final UUID TENANT_ID = UUID.randomUUID();
  private static final UUID ACTOR_ID = UUID.randomUUID();

  private final TenantPolicyService service = new TenantPolicyService();

  @Test
  void ownerAdminCanApproveConnectorCommand() {
    TenantPolicyDecision decision = service.evaluate(context(ActorRole.OWNER_ADMIN, TenantPolicyAction.APPROVE_CONNECTOR_COMMAND).build());

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.reasonCode()).isEqualTo("ROLE_ACTION_ALLOWED");
  }

  @Test
  void operatorCannotApproveConnectorCommand() {
    TenantPolicyDecision decision = service.evaluate(context(ActorRole.OPERATOR, TenantPolicyAction.APPROVE_CONNECTOR_COMMAND).build());

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo("ACTION_NOT_ALLOWED");
  }

  @Test
  void integrationAdminCanCreateConnectorCommandButCannotApproveIt() {
    assertThat(service.evaluate(context(ActorRole.INTEGRATION_ADMIN, TenantPolicyAction.CREATE_CONNECTOR_COMMAND).build()).allowed()).isTrue();

    TenantPolicyDecision approveDecision = service.evaluate(context(ActorRole.INTEGRATION_ADMIN, TenantPolicyAction.APPROVE_CONNECTOR_COMMAND).build());

    assertThat(approveDecision.allowed()).isFalse();
    assertThat(approveDecision.reasonCode()).isEqualTo("ACTION_NOT_ALLOWED");
  }

  @Test
  void humanRolesCannotExecuteConnectorCommand() {
    TenantPolicyDecision decision = service.evaluate(context(ActorRole.OWNER_ADMIN, TenantPolicyAction.EXECUTE_CONNECTOR_COMMAND)
        .connectorCommandState(ConnectorCommandExecutionState.EXECUTION_READY)
        .approved(true)
        .executionMode(ExecutionMode.DRY_RUN)
        .build());

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo("CONNECTOR_EXECUTION_HUMAN_DENIED");
  }

  @Test
  void systemConnectorWorkerCanPassExecutionPolicyOnlyForApprovedReadyDryRunCommand() {
    TenantPolicyContext allowedContext = TenantPolicyContext.builder()
        .tenantId(TENANT_ID)
        .targetTenantId(TENANT_ID)
        .actorRoles(Set.of(ActorRole.SYSTEM_CONNECTOR_WORKER))
        .systemActor(true)
        .action(TenantPolicyAction.EXECUTE_CONNECTOR_COMMAND)
        .resourceType(ResourceType.CONNECTOR_COMMAND)
        .connectorCommandState(ConnectorCommandExecutionState.EXECUTION_READY)
        .approved(true)
        .executionMode(ExecutionMode.DRY_RUN)
        .build();

    assertThat(service.evaluate(allowedContext).allowed()).isTrue();

    TenantPolicyDecision unapprovedDecision = service.evaluate(TenantPolicyContext.builder()
        .tenantId(TENANT_ID)
        .targetTenantId(TENANT_ID)
        .actorRoles(Set.of(ActorRole.SYSTEM_CONNECTOR_WORKER))
        .systemActor(true)
        .action(TenantPolicyAction.EXECUTE_CONNECTOR_COMMAND)
        .resourceType(ResourceType.CONNECTOR_COMMAND)
        .connectorCommandState(ConnectorCommandExecutionState.EXECUTION_READY)
        .approved(false)
        .executionMode(ExecutionMode.DRY_RUN)
        .build());
    assertThat(unapprovedDecision.allowed()).isFalse();
    assertThat(unapprovedDecision.reasonCode()).isEqualTo("CONNECTOR_NOT_READY");

    TenantPolicyDecision realExecutionDecision = service.evaluate(TenantPolicyContext.builder()
        .tenantId(TENANT_ID)
        .targetTenantId(TENANT_ID)
        .actorRoles(Set.of(ActorRole.SYSTEM_CONNECTOR_WORKER))
        .systemActor(true)
        .action(TenantPolicyAction.EXECUTE_CONNECTOR_COMMAND)
        .resourceType(ResourceType.CONNECTOR_COMMAND)
        .connectorCommandState(ConnectorCommandExecutionState.EXECUTION_READY)
        .approved(true)
        .executionMode(ExecutionMode.REAL)
        .build());
    assertThat(realExecutionDecision.allowed()).isFalse();
    assertThat(realExecutionDecision.reasonCode()).isEqualTo("REAL_EXECUTION_DISABLED_STAGE_10H");
  }

  @Test
  void auditorCanViewAuditLogButCannotMutateWorkflows() {
    assertThat(service.evaluate(context(ActorRole.AUDITOR, TenantPolicyAction.VIEW_AUDIT_LOG).build()).allowed()).isTrue();

    TenantPolicyDecision mutationDecision = service.evaluate(context(ActorRole.AUDITOR, TenantPolicyAction.APPROVE_QUOTE).build());

    assertThat(mutationDecision.allowed()).isFalse();
    assertThat(mutationDecision.reasonCode()).isEqualTo("ACTION_NOT_ALLOWED");
  }

  @Test
  void botManagerCanManageChannelSettingsButCannotApproveQuotesOrMarginSensitiveActions() {
    assertThat(service.evaluate(context(ActorRole.BOT_MANAGER, TenantPolicyAction.MANAGE_CHANNEL_SETTINGS).build()).allowed()).isTrue();
    assertThat(service.evaluate(context(ActorRole.BOT_MANAGER, TenantPolicyAction.MANAGE_BOT_FLOWS).build()).allowed()).isTrue();

    TenantPolicyDecision approveQuoteDecision = service.evaluate(context(ActorRole.BOT_MANAGER, TenantPolicyAction.APPROVE_QUOTE).build());
    TenantPolicyDecision marginDecision = service.evaluate(context(ActorRole.BOT_MANAGER, TenantPolicyAction.APPROVE_BELOW_MARGIN_DISCOUNT).build());

    assertThat(approveQuoteDecision.allowed()).isFalse();
    assertThat(marginDecision.allowed()).isFalse();
  }

  @Test
  void readOnlyViewerCannotMutateAnything() {
    TenantPolicyDecision decision = service.evaluate(context(ActorRole.READ_ONLY_VIEWER, TenantPolicyAction.CREATE_DRAFT_QUOTE).build());

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo("ACTION_NOT_ALLOWED");
  }

  @Test
  void missingTenantContextIsDenied() {
    TenantPolicyDecision decision = service.evaluate(TenantPolicyContext.builder()
        .targetTenantId(TENANT_ID)
        .actorId(ACTOR_ID)
        .actorRoles(Set.of(ActorRole.OWNER_ADMIN))
        .action(TenantPolicyAction.VIEW_DOCUMENTS)
        .build());

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo("MISSING_TENANT");
  }

  @Test
  void actorTenantMismatchIsDenied() {
    TenantPolicyDecision decision = service.evaluate(context(ActorRole.OWNER_ADMIN, TenantPolicyAction.VIEW_DOCUMENTS)
        .targetTenantId(UUID.randomUUID())
        .build());

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo("TENANT_MISMATCH");
  }

  @Test
  void unsupportedRoleIsDenied() {
    TenantPolicyDecision decision = service.evaluate(TenantPolicyContext.builder()
        .tenantId(TENANT_ID)
        .targetTenantId(TENANT_ID)
        .actorId(ACTOR_ID)
        .actorRoles(Set.of())
        .action(TenantPolicyAction.VIEW_DOCUMENTS)
        .build());

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo("UNSUPPORTED_ROLE");
  }

  @Test
  void belowMarginDiscountApprovalIsDeniedForOperator() {
    TenantPolicyDecision decision = service.evaluate(context(ActorRole.OPERATOR, TenantPolicyAction.APPROVE_BELOW_MARGIN_DISCOUNT).build());

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo("SENSITIVE_ACTION_DENIED");
  }

  @Test
  void compensationPlanApprovalIsDeniedForOperator() {
    TenantPolicyDecision decision = service.evaluate(context(ActorRole.OPERATOR, TenantPolicyAction.APPROVE_COMPENSATION_PLAN).build());

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo("SENSITIVE_ACTION_DENIED");
  }

  @Test
  void compensationPlanApprovalIsAllowedForOwnerAdmin() {
    TenantPolicyDecision decision = service.evaluate(context(ActorRole.OWNER_ADMIN, TenantPolicyAction.APPROVE_COMPENSATION_PLAN).build());

    assertThat(decision.allowed()).isTrue();
  }

  @Test
  void tenantIdIsPreservedInPolicyContextAndDenialResult() {
    TenantPolicyDecision decision = service.evaluate(context(ActorRole.OPERATOR, TenantPolicyAction.APPROVE_CONNECTOR_COMMAND).build());

    assertThat(decision.tenantId()).isEqualTo(TENANT_ID);
    assertThat(decision.action()).isEqualTo(TenantPolicyAction.APPROVE_CONNECTOR_COMMAND);
  }

  @Test
  void requireAllowedThrowsExplicitPolicyExceptionOnDenial() {
    TenantPolicyContext deniedContext = context(ActorRole.OPERATOR, TenantPolicyAction.APPROVE_CONNECTOR_COMMAND).build();

    assertThatThrownBy(() -> service.requireAllowed(deniedContext))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("Role is not allowed");
  }

  private TenantPolicyContext.Builder context(ActorRole role, TenantPolicyAction action) {
    return TenantPolicyContext.builder()
        .tenantId(TENANT_ID)
        .targetTenantId(TENANT_ID)
        .actorId(ACTOR_ID)
        .actorRoles(Set.of(role))
        .action(action);
  }
}
