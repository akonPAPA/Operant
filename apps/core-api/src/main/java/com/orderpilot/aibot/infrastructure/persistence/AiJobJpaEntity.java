package com.orderpilot.aibot.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "aibot_ai_job",
    uniqueConstraints = {
      @UniqueConstraint(name = "uq_aibot_ai_job_public_id", columnNames = "public_id"),
      @UniqueConstraint(
          name = "uq_aibot_ai_job_idempotency",
          columnNames = {"tenant_id", "purpose", "idempotency_key"})
    })
public class AiJobJpaEntity {
  @Id @GeneratedValue private UUID id;

  @Column(name = "public_id", nullable = false, updatable = false, length = 40)
  private String publicId;

  @Column(name = "tenant_id", nullable = false, updatable = false)
  private UUID tenantId;

  @Column(nullable = false, length = 64)
  private String purpose;

  @Column(name = "bot_definition_version_id", nullable = false, updatable = false)
  private UUID botDefinitionVersionId;

  @Column(nullable = false, length = 32)
  private String status;

  @Column(name = "provider_key", nullable = false, length = 64)
  private String providerKey;

  @Column(name = "model_key", nullable = false, length = 64)
  private String modelKey;

  @Column(name = "request_schema_version", length = 64)
  private String requestSchemaVersion;

  @Column(name = "response_schema_version", length = 64)
  private String responseSchemaVersion;

  @Column(name = "input_hash", length = 128)
  private String inputHash;

  @Column(name = "output_hash", length = 128)
  private String outputHash;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "request_json", nullable = false, columnDefinition = "jsonb")
  private String requestJson;

  @Column(name = "request_fingerprint", length = 128)
  private String requestFingerprint;

  @Column(name = "input_classification", length = 64)
  private String inputClassification;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "result_json", nullable = false, columnDefinition = "jsonb")
  private String resultJson;

  @Column(name = "failure_class", length = 80)
  private String failureClass;

  @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
  private String idempotencyKey;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "lease_owner", length = 128)
  private String leaseOwner;

  @Column(name = "lease_until")
  private Instant leaseUntil;

  @Column(name = "fencing_token", nullable = false)
  private long fencingToken;

  @Column(name = "next_attempt_at")
  private Instant nextAttemptAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Version
  @Column(name = "row_version", nullable = false)
  private long rowVersion;

  protected AiJobJpaEntity() {}

  public AiJobJpaEntity(
      String publicId,
      UUID tenantId,
      String purpose,
      UUID botDefinitionVersionId,
      String status,
      String idempotencyKey,
      String requestSchemaVersion,
      Instant now) {
    this.publicId = publicId;
    this.tenantId = tenantId;
    this.purpose = purpose;
    this.botDefinitionVersionId = botDefinitionVersionId;
    this.status = status;
    this.providerKey = "none";
    this.modelKey = "none";
    this.requestSchemaVersion = requestSchemaVersion;
    this.requestJson = "{}";
    this.resultJson = "{}";
    this.idempotencyKey = idempotencyKey;
    this.attemptCount = 0;
    this.fencingToken = 0L;
    this.createdAt = now;
  }

  public void overwriteFromDomain(
      String status,
      String providerKey,
      String modelKey,
      String requestSchemaVersion,
      String responseSchemaVersion,
      String inputHash,
      String outputHash,
      String requestJson,
      String requestFingerprint,
      String inputClassification,
      String resultJson,
      String failureClass,
      int attemptCount,
      String leaseOwner,
      Instant leaseUntil,
      long fencingToken,
      Instant nextAttemptAt,
      Instant startedAt,
      Instant completedAt) {
    this.status = status;
    this.providerKey = providerKey;
    this.modelKey = modelKey;
    this.requestSchemaVersion = requestSchemaVersion;
    this.responseSchemaVersion = responseSchemaVersion;
    this.inputHash = inputHash;
    this.outputHash = outputHash;
    this.requestJson = requestJson == null ? "{}" : requestJson;
    this.requestFingerprint = requestFingerprint;
    this.inputClassification = inputClassification;
    this.resultJson = resultJson == null ? "{}" : resultJson;
    this.failureClass = failureClass;
    this.attemptCount = attemptCount;
    this.leaseOwner = leaseOwner;
    this.leaseUntil = leaseUntil;
    this.fencingToken = fencingToken;
    this.nextAttemptAt = nextAttemptAt;
    this.startedAt = startedAt;
    this.completedAt = completedAt;
  }

  public void applyClaim(String workerId, Instant leaseUntil, Instant now) {
    this.leaseOwner = workerId;
    this.leaseUntil = leaseUntil;
    this.fencingToken = this.fencingToken + 1L;
    this.attemptCount = this.attemptCount + 1;
    this.status = "LEASED";
    this.startedAt = now;
    this.nextAttemptAt = null;
  }

  public UUID getId() { return id; }
  public String getPublicId() { return publicId; }
  public UUID getTenantId() { return tenantId; }
  public String getPurpose() { return purpose; }
  public UUID getBotDefinitionVersionId() { return botDefinitionVersionId; }
  public String getStatus() { return status; }
  public String getProviderKey() { return providerKey; }
  public String getModelKey() { return modelKey; }
  public String getRequestSchemaVersion() { return requestSchemaVersion; }
  public String getResponseSchemaVersion() { return responseSchemaVersion; }
  public String getInputHash() { return inputHash; }
  public String getOutputHash() { return outputHash; }
  public String getRequestJson() { return requestJson; }
  public String getRequestFingerprint() { return requestFingerprint; }
  public String getInputClassification() { return inputClassification; }
  public String getResultJson() { return resultJson; }
  public String getFailureClass() { return failureClass; }
  public String getIdempotencyKey() { return idempotencyKey; }
  public int getAttemptCount() { return attemptCount; }
  public String getLeaseOwner() { return leaseOwner; }
  public Instant getLeaseUntil() { return leaseUntil; }
  public long getFencingToken() { return fencingToken; }
  public Instant getNextAttemptAt() { return nextAttemptAt; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getStartedAt() { return startedAt; }
  public Instant getCompletedAt() { return completedAt; }
  public long getRowVersion() { return rowVersion; }
}
