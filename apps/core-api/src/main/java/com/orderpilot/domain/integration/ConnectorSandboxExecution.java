package com.orderpilot.domain.integration;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "connector_sandbox_execution",
    uniqueConstraints = @UniqueConstraint(name = "uq_connector_sandbox_execution_tenant_key", columnNames = {"tenant_id", "dry_run_key"}))
public class ConnectorSandboxExecution {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "connector_command_id", nullable = false) private UUID connectorCommandId;
  @Column(name = "change_request_id") private UUID changeRequestId;
  @Column(name = "target_system_type", nullable = false) private String targetSystemType;
  @Column(name = "requested_by_actor_id") private UUID requestedByActorId;
  @Column(name = "execution_mode", nullable = false) private String executionMode;
  @Enumerated(EnumType.STRING) @Column(nullable = false) private ConnectorSandboxExecutionStatus status;
  @Column(name = "dry_run_key", nullable = false) private String dryRunKey;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "generated_payload_json", columnDefinition = "jsonb", nullable = false) private String generatedPayloadJson;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "simulated_provider_response_json", columnDefinition = "jsonb", nullable = false) private String simulatedProviderResponseJson;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "validation_summary_json", columnDefinition = "jsonb", nullable = false) private String validationSummaryJson;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "warnings_json", columnDefinition = "jsonb", nullable = false) private String warningsJson;
  @Column(name = "error_code") private String errorCode;
  @Column(name = "error_message") private String errorMessage;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "started_at") private Instant startedAt;
  @Column(name = "completed_at") private Instant completedAt;
  @Column(name = "audit_correlation_id") private UUID auditCorrelationId;

  protected ConnectorSandboxExecution() {}

  public ConnectorSandboxExecution(UUID tenantId, ConnectorCommand command, UUID requestedByActorId, String dryRunKey, Instant now) {
    this.tenantId = tenantId;
    this.connectorCommandId = command.getId();
    this.changeRequestId = command.getChangeRequestId();
    this.targetSystemType = command.getConnectorType();
    this.requestedByActorId = requestedByActorId;
    this.executionMode = "DRY_RUN";
    this.status = ConnectorSandboxExecutionStatus.REQUESTED;
    this.dryRunKey = dryRunKey;
    this.generatedPayloadJson = "{}";
    this.simulatedProviderResponseJson = "{}";
    this.validationSummaryJson = "{}";
    this.warningsJson = "[]";
    this.createdAt = now;
    this.auditCorrelationId = UUID.randomUUID();
  }

  public void markPolicyBlocked(String errorCode, String errorMessage, Instant now) {
    this.status = ConnectorSandboxExecutionStatus.POLICY_BLOCKED;
    this.errorCode = errorCode;
    this.errorMessage = errorMessage;
    this.validationSummaryJson = "{\"policyAllowed\":false,\"reasonCode\":\"" + safe(errorCode) + "\"}";
    this.completedAt = now;
  }

  public void markValidationFailed(String errorCode, String errorMessage, String validationSummaryJson, String warningsJson, Instant now) {
    this.status = ConnectorSandboxExecutionStatus.VALIDATION_FAILED;
    this.errorCode = errorCode;
    this.errorMessage = errorMessage;
    this.validationSummaryJson = jsonObject(validationSummaryJson);
    this.warningsJson = jsonArray(warningsJson);
    this.completedAt = now;
  }

  public void markRunning(String generatedPayloadJson, String validationSummaryJson, String warningsJson, Instant now) {
    this.status = ConnectorSandboxExecutionStatus.RUNNING;
    this.generatedPayloadJson = jsonObject(generatedPayloadJson);
    this.validationSummaryJson = jsonObject(validationSummaryJson);
    this.warningsJson = jsonArray(warningsJson);
    this.startedAt = now;
  }

  public void markSucceeded(String simulatedProviderResponseJson, String warningsJson, Instant now) {
    this.status = ConnectorSandboxExecutionStatus.SUCCEEDED;
    this.simulatedProviderResponseJson = jsonObject(simulatedProviderResponseJson);
    this.warningsJson = jsonArray(warningsJson);
    this.completedAt = now;
  }

  public void markFailed(String errorCode, String errorMessage, String simulatedProviderResponseJson, String warningsJson, Instant now) {
    this.status = ConnectorSandboxExecutionStatus.FAILED;
    this.errorCode = errorCode;
    this.errorMessage = errorMessage;
    this.simulatedProviderResponseJson = jsonObject(simulatedProviderResponseJson);
    this.warningsJson = jsonArray(warningsJson);
    this.completedAt = now;
  }

  private static String jsonObject(String value) {
    return value == null || value.isBlank() ? "{}" : value;
  }

  private static String jsonArray(String value) {
    return value == null || value.isBlank() ? "[]" : value;
  }

  private static String safe(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getConnectorCommandId() { return connectorCommandId; }
  public UUID getChangeRequestId() { return changeRequestId; }
  public String getTargetSystemType() { return targetSystemType; }
  public UUID getRequestedByActorId() { return requestedByActorId; }
  public String getExecutionMode() { return executionMode; }
  public ConnectorSandboxExecutionStatus getStatus() { return status; }
  public String getDryRunKey() { return dryRunKey; }
  public String getGeneratedPayloadJson() { return generatedPayloadJson; }
  public String getSimulatedProviderResponseJson() { return simulatedProviderResponseJson; }
  public String getValidationSummaryJson() { return validationSummaryJson; }
  public String getWarningsJson() { return warningsJson; }
  public String getErrorCode() { return errorCode; }
  public String getErrorMessage() { return errorMessage; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getStartedAt() { return startedAt; }
  public Instant getCompletedAt() { return completedAt; }
  public UUID getAuditCorrelationId() { return auditCorrelationId; }
}
