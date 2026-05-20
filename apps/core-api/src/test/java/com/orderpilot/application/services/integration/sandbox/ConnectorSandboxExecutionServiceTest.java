package com.orderpilot.application.services.integration.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.integration.ChangeRequestService;
import com.orderpilot.application.services.integration.ConnectorIdempotencyService;
import com.orderpilot.application.services.integration.CompensationPlanningService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.common.tenant.TenantContextMissingException;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.integration.*;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.policy.*;
import java.time.Instant;
import java.util.Set;
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
@Import({
    ConnectorSandboxExecutionService.class,
    SandboxConnectorAdapterRegistry.class,
    DemoErpSandboxConnectorAdapter.class,
    TenantPolicyService.class,
    ConnectorIdempotencyService.class,
    ChangeRequestService.class,
    CompensationPlanningService.class,
    AuditEventService.class,
    CoreConfiguration.class
})
class ConnectorSandboxExecutionServiceTest {
  @Autowired private ConnectorSandboxExecutionService service;
  @Autowired private ConnectorIdempotencyService idempotencyService;
  @Autowired private ChangeRequestService changeRequestService;
  @Autowired private ConnectorCommandRepository commandRepository;
  @Autowired private ConnectorSandboxExecutionRepository executionRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private CompensationPlanRepository compensationPlanRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void dryRunSucceedsForApprovedExecutionDisabledCommandUnderSystemWorkerPolicy() {
    TenantContext.setTenantId(UUID.randomUUID());
    ConnectorCommand command = approvedCommand("DEMO_ERP", "CREATE_DRAFT_QUOTE", "{\"draftQuoteId\":\"draft-1\"}");

    ConnectorSandboxExecutionResult result = service.executeDryRun(command.getId(), "dry-run-success", null);

    assertThat(result.allowed()).isTrue();
    assertThat(result.status()).isEqualTo(ConnectorSandboxExecutionStatus.SUCCEEDED);
    assertThat(result.execution().getExecutionMode()).isEqualTo("DRY_RUN");
    assertThat(result.execution().getGeneratedPayloadJson()).contains("\"provider\":\"demo-erp-sandbox\"");
    assertThat(result.execution().getGeneratedPayloadJson()).contains("\"externalWritePerformed\":false");
    assertThat(result.execution().getSimulatedProviderResponseJson()).contains("\"sandboxReference\":\"sandbox-dryrun-");
    assertThat(result.execution().getValidationSummaryJson()).contains("\"adapter\":\"demo-erp-sandbox\"");
    assertThat(commandRepository.findById(command.getId()).orElseThrow().getStatus()).isEqualTo("EXECUTION_DISABLED");
    assertThat(auditEventRepository.findAll()).extracting("action").contains(
        "CONNECTOR_SANDBOX_DRY_RUN_REQUESTED",
        "CONNECTOR_SANDBOX_DRY_RUN_SUCCEEDED");
  }

  @Test
  void missingTenantIsDeniedBeforeAnySandboxExecutionIsPersisted() {
    assertThatThrownBy(() -> service.executeDryRun(UUID.randomUUID(), "missing-tenant", null))
        .isInstanceOf(TenantContextMissingException.class)
        .hasMessageContaining("Tenant context is required");
    assertThat(executionRepository.count()).isZero();
  }

  @Test
  void tenantMismatchIsPolicyBlockedAndDoesNotCallAdapter() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ConnectorCommand command = approvedCommand("DEMO_ERP", "CREATE_DRAFT_QUOTE", "{}");

    ConnectorSandboxExecutionResult result = service.executeDryRunWithPolicy(command.getId(), "tenant-mismatch", null,
        policyContext(tenantId, UUID.randomUUID(), ActorRole.SYSTEM_CONNECTOR_WORKER, true, true, ExecutionMode.DRY_RUN));

    assertThat(result.allowed()).isFalse();
    assertThat(result.status()).isEqualTo(ConnectorSandboxExecutionStatus.POLICY_BLOCKED);
    assertThat(result.reasonCode()).isEqualTo("TENANT_MISMATCH");
    assertThat(result.execution().getGeneratedPayloadJson()).isEqualTo("{}");
    assertThat(auditEventRepository.findAll()).extracting("action").contains("CONNECTOR_SANDBOX_DRY_RUN_POLICY_BLOCKED");
  }

  @Test
  void humanRolesAndRealExecutionModeAreDeniedByPolicy() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ConnectorCommand humanDenied = approvedCommand("DEMO_ERP", "CREATE_DRAFT_QUOTE", "{}");
    ConnectorSandboxExecutionResult humanResult = service.executeDryRunWithPolicy(humanDenied.getId(), "human-denied", UUID.randomUUID(),
        policyContext(tenantId, tenantId, ActorRole.OWNER_ADMIN, false, true, ExecutionMode.DRY_RUN));

    ConnectorCommand realDenied = approvedCommand("DEMO_ERP", "CREATE_DRAFT_QUOTE", "{\"second\":true}");
    ConnectorSandboxExecutionResult realResult = service.executeDryRunWithPolicy(realDenied.getId(), "real-denied", null,
        policyContext(tenantId, tenantId, ActorRole.SYSTEM_CONNECTOR_WORKER, true, true, ExecutionMode.REAL));

    assertThat(humanResult.status()).isEqualTo(ConnectorSandboxExecutionStatus.POLICY_BLOCKED);
    assertThat(humanResult.reasonCode()).isEqualTo("CONNECTOR_EXECUTION_HUMAN_DENIED");
    assertThat(realResult.status()).isEqualTo(ConnectorSandboxExecutionStatus.POLICY_BLOCKED);
    assertThat(realResult.reasonCode()).isEqualTo("REAL_EXECUTION_DISABLED_STAGE_10H");
  }

  @Test
  void unapprovedOrNotReadyCommandIsDeniedBeforeAdapterSimulation() {
    TenantContext.setTenantId(UUID.randomUUID());
    ConnectorCommand command = approvedCommand("DEMO_ERP", "CREATE_DRAFT_QUOTE", "{}");
    command.markDeadLettered("not ready", false, null, Instant.parse("2026-05-20T00:00:00Z"));
    commandRepository.save(command);

    ConnectorSandboxExecutionResult result = service.executeDryRun(command.getId(), "not-ready", null);

    assertThat(result.allowed()).isFalse();
    assertThat(result.status()).isEqualTo(ConnectorSandboxExecutionStatus.VALIDATION_FAILED);
    assertThat(result.reasonCode()).isEqualTo("CONNECTOR_NOT_DRY_RUN_READY");
    assertThat(result.execution().getGeneratedPayloadJson()).isEqualTo("{}");
  }

  @Test
  void unsupportedTargetSystemFailsSafelyWithoutExternalExecution() {
    TenantContext.setTenantId(UUID.randomUUID());
    ConnectorCommand command = approvedCommand("ONE_C", "CREATE_DRAFT_QUOTE", "{}");

    ConnectorSandboxExecutionResult result = service.executeDryRun(command.getId(), "unsupported-target", null);

    assertThat(result.allowed()).isFalse();
    assertThat(result.status()).isEqualTo(ConnectorSandboxExecutionStatus.FAILED);
    assertThat(result.reasonCode()).isEqualTo("UNSUPPORTED_SANDBOX_TARGET");
    assertThat(result.execution().getSimulatedProviderResponseJson()).contains("\"externalWritePerformed\":false");
  }

  @Test
  void adapterValidationFailureIsPersistedAsValidationFailed() {
    TenantContext.setTenantId(UUID.randomUUID());
    ConnectorCommand command = approvedCommand("DEMO_ERP", "CREATE_DRAFT_QUOTE", "[]");

    ConnectorSandboxExecutionResult result = service.executeDryRun(command.getId(), "validation-failure", null);

    assertThat(result.allowed()).isFalse();
    assertThat(result.status()).isEqualTo(ConnectorSandboxExecutionStatus.VALIDATION_FAILED);
    assertThat(result.reasonCode()).isEqualTo("SANDBOX_PAYLOAD_INVALID");
    assertThat(result.execution().getValidationSummaryJson()).contains("\"externalWritePerformed\":false");
  }

  @Test
  void idempotencyReturnsExistingResultWithoutDuplicateContradictoryRecords() {
    TenantContext.setTenantId(UUID.randomUUID());
    ConnectorCommand command = approvedCommand("DEMO_ERP", "CREATE_DRAFT_QUOTE", "{\"draftQuoteId\":\"draft-1\"}");

    ConnectorSandboxExecutionResult first = service.executeDryRun(command.getId(), "same-dry-run-key", null);
    ConnectorSandboxExecutionResult second = service.executeDryRun(command.getId(), "same-dry-run-key", null);

    assertThat(second.execution().getId()).isEqualTo(first.execution().getId());
    assertThat(executionRepository.count()).isEqualTo(1);
    assertThat(second.reasonCode()).isEqualTo("DRY_RUN_IDEMPOTENT_REPLAY");
  }

  @Test
  void dryRunDoesNotCreateCompensationPlanOrMarkExternalExecution() {
    TenantContext.setTenantId(UUID.randomUUID());
    ConnectorCommand command = approvedCommand("DEMO_ERP", "CREATE_DRAFT_QUOTE", "{}");

    service.executeDryRun(command.getId(), "no-compensation", null);

    assertThat(compensationPlanRepository.count()).isZero();
    assertThat(commandRepository.findById(command.getId()).orElseThrow().getStatus()).isEqualTo("EXECUTION_DISABLED");
  }

  private ConnectorCommand approvedCommand(String connectorType, String operationType, String payloadJson) {
    var request = changeRequestService.createChangeRequest(connectorType, "DRAFT_QUOTE", "EXPORT", "DRAFT_QUOTE", UUID.randomUUID(), "{}", UUID.randomUUID().toString(), null);
    changeRequestService.validateChangeRequest(request.getId());
    changeRequestService.approveChangeRequest(request.getId(), UUID.randomUUID());
    return idempotencyService.createCommandFromApprovedChangeRequest(request.getId(), null, connectorType, operationType, payloadJson);
  }

  private TenantPolicyContext policyContext(UUID tenantId, UUID targetTenantId, ActorRole role, boolean systemActor, boolean approved, ExecutionMode mode) {
    return TenantPolicyContext.builder()
        .tenantId(tenantId)
        .targetTenantId(targetTenantId)
        .actorId(systemActor ? null : UUID.randomUUID())
        .actorRoles(Set.of(role))
        .systemActor(systemActor)
        .action(TenantPolicyAction.EXECUTE_CONNECTOR_COMMAND)
        .resourceType(ResourceType.CONNECTOR_COMMAND)
        .connectorCommandState(ConnectorCommandExecutionState.EXECUTION_READY)
        .approved(approved)
        .executionMode(mode)
        .build();
  }
}
