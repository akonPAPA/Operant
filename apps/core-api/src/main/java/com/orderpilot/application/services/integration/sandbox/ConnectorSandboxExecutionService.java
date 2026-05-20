package com.orderpilot.application.services.integration.sandbox;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.integration.*;
import com.orderpilot.security.policy.*;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConnectorSandboxExecutionService {
  private final ConnectorCommandRepository commandRepository;
  private final ConnectorSandboxExecutionRepository executionRepository;
  private final SandboxConnectorAdapterRegistry adapterRegistry;
  private final TenantPolicyService tenantPolicyService;
  private final AuditEventService auditEventService;
  private final Clock clock;

  public ConnectorSandboxExecutionService(
      ConnectorCommandRepository commandRepository,
      ConnectorSandboxExecutionRepository executionRepository,
      SandboxConnectorAdapterRegistry adapterRegistry,
      TenantPolicyService tenantPolicyService,
      AuditEventService auditEventService,
      Clock clock) {
    this.commandRepository = commandRepository;
    this.executionRepository = executionRepository;
    this.adapterRegistry = adapterRegistry;
    this.tenantPolicyService = tenantPolicyService;
    this.auditEventService = auditEventService;
    this.clock = clock;
  }

  @Transactional
  public ConnectorSandboxExecutionResult executeDryRun(UUID connectorCommandId, String dryRunKey, UUID requestedByActorId) {
    UUID tenantId = TenantContext.requireTenantId();
    ConnectorCommand command = commandRepository.findByIdAndTenantId(connectorCommandId, tenantId)
        .orElseThrow(() -> new NotFoundException("Connector command not found: " + connectorCommandId));
    String effectiveKey = normalizeDryRunKey(tenantId, command.getId(), dryRunKey);
    var existing = executionRepository.findByTenantIdAndDryRunKey(tenantId, effectiveKey);
    if (existing.isPresent()) {
      ConnectorSandboxExecution execution = existing.get();
      return ConnectorSandboxExecutionResult.fromExecution(
          execution.getStatus() == ConnectorSandboxExecutionStatus.SUCCEEDED,
          "DRY_RUN_IDEMPOTENT_REPLAY",
          "Existing sandbox dry-run result returned for the same tenant and key",
          execution);
    }

    auditEventService.record("CONNECTOR_SANDBOX_DRY_RUN_REQUESTED", "CONNECTOR_COMMAND", command.getId().toString(), requestedByActorId, "{\"executionMode\":\"DRY_RUN\"}");
    ConnectorSandboxExecution execution = executionRepository.save(new ConnectorSandboxExecution(tenantId, command, requestedByActorId, effectiveKey, clock.instant()));
    TenantPolicyContext context = systemDryRunPolicyContext(tenantId, command);
    return executeWithPolicy(command, execution, context);
  }

  @Transactional
  public ConnectorSandboxExecutionResult executeDryRunWithPolicy(UUID connectorCommandId, String dryRunKey, UUID requestedByActorId, TenantPolicyContext policyContext) {
    UUID tenantId = TenantContext.requireTenantId();
    ConnectorCommand command = commandRepository.findByIdAndTenantId(connectorCommandId, tenantId)
        .orElseThrow(() -> new NotFoundException("Connector command not found: " + connectorCommandId));
    String effectiveKey = normalizeDryRunKey(tenantId, command.getId(), dryRunKey);
    var existing = executionRepository.findByTenantIdAndDryRunKey(tenantId, effectiveKey);
    if (existing.isPresent()) {
      return ConnectorSandboxExecutionResult.fromExecution(false, "DRY_RUN_IDEMPOTENT_REPLAY", "Existing sandbox dry-run result returned for the same tenant and key", existing.get());
    }
    auditEventService.record("CONNECTOR_SANDBOX_DRY_RUN_REQUESTED", "CONNECTOR_COMMAND", command.getId().toString(), requestedByActorId, "{\"executionMode\":\"DRY_RUN\"}");
    ConnectorSandboxExecution execution = executionRepository.save(new ConnectorSandboxExecution(tenantId, command, requestedByActorId, effectiveKey, clock.instant()));
    return executeWithPolicy(command, execution, policyContext);
  }

  @Transactional(readOnly = true)
  public ConnectorSandboxExecution getExecution(UUID id) {
    UUID tenantId = TenantContext.requireTenantId();
    return executionRepository.findByIdAndTenantId(id, tenantId)
        .orElseThrow(() -> new NotFoundException("Connector sandbox execution not found: " + id));
  }

  private ConnectorSandboxExecutionResult executeWithPolicy(ConnectorCommand command, ConnectorSandboxExecution execution, TenantPolicyContext policyContext) {
    TenantPolicyDecision decision = tenantPolicyService.evaluate(policyContext);
    if (!decision.allowed()) {
      execution.markPolicyBlocked(decision.reasonCode(), decision.message(), clock.instant());
      auditEventService.record("CONNECTOR_SANDBOX_DRY_RUN_POLICY_BLOCKED", "CONNECTOR_SANDBOX_EXECUTION", execution.getId().toString(), execution.getRequestedByActorId(), "{\"reasonCode\":\"" + escape(decision.reasonCode()) + "\"}");
      return ConnectorSandboxExecutionResult.fromExecution(false, decision.reasonCode(), decision.message(), executionRepository.save(execution));
    }
    if (!isDryRunReady(command)) {
      execution.markValidationFailed("CONNECTOR_NOT_DRY_RUN_READY", "Connector command is not approved/ready for sandbox dry-run", "{\"commandStatus\":\"" + escape(command.getStatus()) + "\",\"externalWritePerformed\":false}", "[\"Command was not passed to a sandbox adapter.\"]", clock.instant());
      auditEventService.record("CONNECTOR_SANDBOX_DRY_RUN_FAILED", "CONNECTOR_SANDBOX_EXECUTION", execution.getId().toString(), execution.getRequestedByActorId(), "{\"reasonCode\":\"CONNECTOR_NOT_DRY_RUN_READY\"}");
      return ConnectorSandboxExecutionResult.fromExecution(false, "CONNECTOR_NOT_DRY_RUN_READY", execution.getErrorMessage(), executionRepository.save(execution));
    }

    SandboxConnectorAdapter adapter;
    try {
      adapter = adapterRegistry.requireAdapter(command.getConnectorType());
    } catch (IllegalArgumentException ex) {
      execution.markFailed("UNSUPPORTED_SANDBOX_TARGET", ex.getMessage(), "{\"externalWritePerformed\":false}", "[\"Unsupported connector type was not executed.\"]", clock.instant());
      auditEventService.record("CONNECTOR_SANDBOX_DRY_RUN_FAILED", "CONNECTOR_SANDBOX_EXECUTION", execution.getId().toString(), execution.getRequestedByActorId(), "{\"reasonCode\":\"UNSUPPORTED_SANDBOX_TARGET\"}");
      return ConnectorSandboxExecutionResult.fromExecution(false, "UNSUPPORTED_SANDBOX_TARGET", ex.getMessage(), executionRepository.save(execution));
    }

    String generatedPayload = adapter.buildDryRunPayload(command);
    SandboxValidationResult validation = adapter.validateDryRun(command, generatedPayload);
    if (!validation.valid()) {
      execution.markValidationFailed(validation.errorCode(), validation.errorMessage(), validation.summaryJson(), validation.warningsJson(), clock.instant());
      auditEventService.record("CONNECTOR_SANDBOX_DRY_RUN_FAILED", "CONNECTOR_SANDBOX_EXECUTION", execution.getId().toString(), execution.getRequestedByActorId(), "{\"reasonCode\":\"" + escape(validation.errorCode()) + "\"}");
      return ConnectorSandboxExecutionResult.fromExecution(false, validation.errorCode(), validation.errorMessage(), executionRepository.save(execution));
    }

    execution.markRunning(generatedPayload, validation.summaryJson(), validation.warningsJson(), clock.instant());
    SandboxSimulationResult simulation = adapter.simulate(command, generatedPayload);
    if (simulation.success()) {
      execution.markSucceeded(simulation.responseJson(), simulation.warningsJson(), clock.instant());
      auditEventService.record("CONNECTOR_SANDBOX_DRY_RUN_SUCCEEDED", "CONNECTOR_SANDBOX_EXECUTION", execution.getId().toString(), execution.getRequestedByActorId(), "{\"executionMode\":\"DRY_RUN\",\"externalWritePerformed\":false}");
      return ConnectorSandboxExecutionResult.fromExecution(true, "SANDBOX_DRY_RUN_SUCCEEDED", "Sandbox dry-run completed without external execution", executionRepository.save(execution));
    }
    execution.markFailed(simulation.errorCode(), simulation.errorMessage(), simulation.responseJson(), simulation.warningsJson(), clock.instant());
    auditEventService.record("CONNECTOR_SANDBOX_DRY_RUN_FAILED", "CONNECTOR_SANDBOX_EXECUTION", execution.getId().toString(), execution.getRequestedByActorId(), "{\"reasonCode\":\"" + escape(simulation.errorCode()) + "\"}");
    return ConnectorSandboxExecutionResult.fromExecution(false, simulation.errorCode(), simulation.errorMessage(), executionRepository.save(execution));
  }

  private TenantPolicyContext systemDryRunPolicyContext(UUID tenantId, ConnectorCommand command) {
    return TenantPolicyContext.builder()
        .tenantId(tenantId)
        .targetTenantId(command.getTenantId())
        .actorRoles(Set.of(ActorRole.SYSTEM_CONNECTOR_WORKER))
        .systemActor(true)
        .resourceType(ResourceType.CONNECTOR_COMMAND)
        .resourceId(command.getId())
        .action(TenantPolicyAction.EXECUTE_CONNECTOR_COMMAND)
        .connectorCommandState(ConnectorCommandExecutionState.EXECUTION_READY)
        .approved(true)
        .executionMode(ExecutionMode.DRY_RUN)
        .build();
  }

  private static boolean isDryRunReady(ConnectorCommand command) {
    return "EXECUTION_DISABLED".equals(command.getStatus()) || "READY_INTERNAL_ONLY".equals(command.getStatus());
  }

  private static String normalizeDryRunKey(UUID tenantId, UUID commandId, String requestedKey) {
    if (requestedKey != null && !requestedKey.isBlank()) {
      return requestedKey;
    }
    return "sandbox-dryrun:" + tenantId + ":" + commandId;
  }

  private static String escape(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
