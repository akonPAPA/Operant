package com.orderpilot.domain.integration;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "connector_command",
    uniqueConstraints = @UniqueConstraint(name = "uq_connector_command_idempotency", columnNames = {"tenant_id", "idempotency_key"}))
public class ConnectorCommand {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "change_request_id", nullable = false) private UUID changeRequestId;
  @Column(name = "outbox_event_id") private UUID outboxEventId;
  @Column(name = "connector_type", nullable = false) private String connectorType;
  @Column(name = "operation_type", nullable = false) private String operationType;
  @Column(name = "idempotency_key", nullable = false) private String idempotencyKey;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "payload_json", columnDefinition = "jsonb", nullable = false) private String payloadJson;
  @Column(nullable = false) private String status;
  @Column(name = "attempt_count", nullable = false) private int attemptCount;
  @Column(name = "max_attempts", nullable = false) private int maxAttempts;
  @Column(name = "next_attempt_at") private Instant nextAttemptAt;
  @Column(name = "last_error") private String lastError;
  @Column(name = "dead_letter_reason") private String deadLetterReason;
  @Column(nullable = false) private boolean retryable;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected ConnectorCommand() {}

  public ConnectorCommand(UUID tenantId, UUID changeRequestId, UUID outboxEventId, String connectorType, String operationType, String idempotencyKey, String payloadJson, Instant now) {
    this.tenantId = tenantId;
    this.changeRequestId = changeRequestId;
    this.outboxEventId = outboxEventId;
    this.connectorType = connectorType;
    this.operationType = operationType;
    this.idempotencyKey = idempotencyKey;
    this.payloadJson = payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson;
    this.status = "EXECUTION_DISABLED";
    this.attemptCount = 0;
    this.maxAttempts = 0;
    this.retryable = false;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void markSkippedExternalDisabled(String reason, Instant now) {
    this.status = "SKIPPED_EXTERNAL_DISABLED";
    this.lastError = reason;
    this.retryable = false;
    this.nextAttemptAt = null;
    this.updatedAt = now;
  }

  public void markDeadLettered(String reason, boolean retryable, Instant nextAttemptAt, Instant now) {
    this.status = "DEAD_LETTERED";
    this.deadLetterReason = reason;
    this.lastError = reason;
    this.retryable = retryable;
    this.nextAttemptAt = nextAttemptAt;
    this.attemptCount = this.attemptCount + 1;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getChangeRequestId() { return changeRequestId; }
  public UUID getOutboxEventId() { return outboxEventId; }
  public String getConnectorType() { return connectorType; }
  public String getOperationType() { return operationType; }
  public String getIdempotencyKey() { return idempotencyKey; }
  public String getPayloadJson() { return payloadJson; }
  public String getStatus() { return status; }
  public int getAttemptCount() { return attemptCount; }
  public int getMaxAttempts() { return maxAttempts; }
  public Instant getNextAttemptAt() { return nextAttemptAt; }
  public String getLastError() { return lastError; }
  public String getDeadLetterReason() { return deadLetterReason; }
  public boolean isRetryable() { return retryable; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
