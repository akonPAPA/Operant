package com.orderpilot.common.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "idempotency_key",
    uniqueConstraints = @UniqueConstraint(name = "uq_idempotency_tenant_key", columnNames = {"tenant_id", "key_hash"}))
public class IdempotencyRecord {
  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "actor_id")
  private UUID actorId;

  @Column(name = "key_hash", nullable = false, length = 128)
  private String keyHash;

  @Column(name = "request_fingerprint", nullable = false, length = 128)
  private String requestHash;

  @Column(name = "command_type", length = 160)
  private String commandType;

  @Column(name = "target_resource_type", length = 120)
  private String targetResourceType;

  @Column(name = "target_resource_id", length = 160)
  private String targetResourceId;

  @Column(nullable = false, length = 40)
  private String status;

  @Column(name = "response_status")
  private Integer responseStatus;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "response_body", columnDefinition = "jsonb")
  private String responseBody;

  @Column(name = "error_code", length = 120)
  private String errorCode;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  protected IdempotencyRecord() {}

  public IdempotencyRecord(
      UUID tenantId,
      UUID actorId,
      String keyHash,
      String requestHash,
      String commandType,
      String targetResourceType,
      String targetResourceId,
      Instant now,
      Instant expiresAt) {
    this.tenantId = tenantId;
    this.actorId = actorId;
    this.keyHash = keyHash;
    this.requestHash = requestHash;
    this.commandType = commandType;
    this.targetResourceType = targetResourceType;
    this.targetResourceId = targetResourceId;
    this.status = "IN_PROGRESS";
    this.createdAt = now;
    this.updatedAt = now;
    this.expiresAt = expiresAt;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getActorId() { return actorId; }
  public String getKeyHash() { return keyHash; }
  public String getRequestHash() { return requestHash; }
  public String getCommandType() { return commandType; }
  public String getTargetResourceType() { return targetResourceType; }
  public String getTargetResourceId() { return targetResourceId; }
  public String getStatus() { return status; }
  public String getResponseBody() { return responseBody; }

  public void markSucceeded(int responseStatus, String responseBody, Instant now) {
    this.status = "SUCCEEDED";
    this.responseStatus = responseStatus;
    this.responseBody = responseBody;
    this.errorCode = null;
    this.updatedAt = now;
  }
}
