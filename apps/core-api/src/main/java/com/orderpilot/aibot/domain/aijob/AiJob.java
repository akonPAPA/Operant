package com.orderpilot.aibot.domain.aijob;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Durable AI provider job. Raw secrets and chain-of-thought are never stored. */
public final class AiJob {

  private UUID id;
  private final String publicId;
  private final UUID tenantId;
  private final AiJobPurpose purpose;
  private final UUID botDefinitionVersionId;
  private AiJobStatus status;
  private String providerKey;
  private String modelKey;
  private String requestSchemaVersion;
  private String responseSchemaVersion;
  private String inputHash;
  private String outputHash;
  private String requestJson;
  private String requestFingerprint;
  private String inputClassification;
  private String resultJson;
  private String failureClass;
  private final String idempotencyKey;
  private int attemptCount;
  private String leaseOwner;
  private Instant leaseUntil;
  private long fencingToken;
  private Instant nextAttemptAt;
  private final Instant createdAt;
  private Instant startedAt;
  private Instant completedAt;
  private long rowVersion;

  public AiJob(
      String publicId,
      UUID tenantId,
      AiJobPurpose purpose,
      UUID botDefinitionVersionId,
      String idempotencyKey,
      String requestSchemaVersion,
      Instant now) {
    this.publicId = Objects.requireNonNull(publicId, "publicId");
    this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
    this.purpose = Objects.requireNonNull(purpose, "purpose");
    this.botDefinitionVersionId = Objects.requireNonNull(botDefinitionVersionId, "botDefinitionVersionId");
    if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
      throw new IllegalArgumentException("invalid_idempotency_key");
    }
    this.idempotencyKey = idempotencyKey.trim();
    this.requestSchemaVersion = requestSchemaVersion;
    this.status = AiJobStatus.REQUESTED;
    this.providerKey = "none";
    this.modelKey = "none";
    this.requestJson = "{}";
    this.resultJson = "{}";
    this.attemptCount = 0;
    this.fencingToken = 0L;
    this.createdAt = Objects.requireNonNull(now, "now");
    this.rowVersion = 0L;
  }

  public void attachRequestEnvelope(
      String requestJson, String requestFingerprint, String inputClassification) {
    if (this.status != AiJobStatus.REQUESTED) {
      throw new IllegalStateException("request_envelope_only_when_requested");
    }
    if (requestJson == null || requestJson.isBlank()) {
      throw new IllegalArgumentException("request_envelope_required");
    }
    if (requestFingerprint == null
        || requestFingerprint.isBlank()
        || requestFingerprint.length() > 128) {
      throw new IllegalArgumentException("invalid_request_fingerprint");
    }
    this.requestJson = requestJson;
    this.requestFingerprint = requestFingerprint.trim();
    this.inputHash = this.requestFingerprint;
    this.inputClassification =
        inputClassification == null || inputClassification.isBlank()
            ? "UNSPECIFIED"
            : inputClassification.trim();
    if (this.inputClassification.length() > 64) {
      throw new IllegalArgumentException("input_classification_too_long");
    }
  }

  /** Applies an atomic repository claim. Attempt count and fencing token are supplied by persistence. */
  public void claim(String leaseOwner, Instant leaseUntil, long fencingToken, int attemptCount, Instant now) {
    if (status.isTerminal()) {
      throw new IllegalStateException("terminal_not_claimable");
    }
    if (status == AiJobStatus.RUNNING && this.leaseUntil != null && this.leaseUntil.isAfter(now)) {
      throw new IllegalStateException("active_lease_blocks_claim");
    }
    if (status != AiJobStatus.REQUESTED
        && !(this.leaseUntil != null && !this.leaseUntil.isAfter(now))
        && !(this.nextAttemptAt != null && !this.nextAttemptAt.isAfter(now))) {
      requireStatus(EnumSet.of(AiJobStatus.REQUESTED, AiJobStatus.LEASED, AiJobStatus.FAILED));
    }
    if (leaseOwner == null || leaseOwner.isBlank() || leaseOwner.length() > 128) {
      throw new IllegalArgumentException("invalid_lease_owner");
    }
    this.status = AiJobStatus.LEASED;
    this.leaseOwner = leaseOwner.trim();
    this.leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil");
    this.fencingToken = fencingToken;
    this.attemptCount = attemptCount;
    this.startedAt = now;
    this.providerKey = this.providerKey == null || this.providerKey.equals("none") ? "pending" : this.providerKey;
    this.modelKey = this.modelKey == null || this.modelKey.equals("none") ? "pending" : this.modelKey;
  }

  public void markRunning(String providerKey, String modelKey, Instant now) {
    transition(AiJobStatus.LEASED, AiJobStatus.RUNNING);
    this.providerKey = requireKey(providerKey, "providerKey");
    this.modelKey = requireKey(modelKey, "modelKey");
    Objects.requireNonNull(now, "now");
  }

  /** @deprecated legacy path until claimed-job processing replaces processRequestedJob. */
  @Deprecated
  public void admit(String providerKey, String modelKey, Instant now) {
    transition(AiJobStatus.REQUESTED, AiJobStatus.ADMITTED);
    this.providerKey = requireKey(providerKey, "providerKey");
    this.modelKey = requireKey(modelKey, "modelKey");
    Objects.requireNonNull(now, "now");
  }

  /** @deprecated legacy path until atomic claim owns attempt increments. */
  @Deprecated
  public void lease(Instant now) {
    requireStatus(EnumSet.of(AiJobStatus.ADMITTED, AiJobStatus.REQUESTED));
    this.status = AiJobStatus.LEASED;
    this.attemptCount++;
    this.startedAt = now;
  }

  /** @deprecated use markRunning(provider, model, now). */
  @Deprecated
  public void markRunning(Instant now) {
    transition(AiJobStatus.LEASED, AiJobStatus.RUNNING);
    Objects.requireNonNull(now, "now");
  }

  public void markOutputReceived(String outputHash, Instant now) {
    transition(AiJobStatus.RUNNING, AiJobStatus.OUTPUT_RECEIVED);
    this.outputHash = outputHash;
    Objects.requireNonNull(now, "now");
  }

  public void markSuggestionReady(String resultJson, String responseSchemaVersion, Instant now) {
    requireStatus(EnumSet.of(AiJobStatus.OUTPUT_RECEIVED, AiJobStatus.VALIDATED, AiJobStatus.RUNNING));
    this.status = AiJobStatus.SUGGESTION_READY;
    this.resultJson = resultJson == null || resultJson.isBlank() ? "{}" : resultJson;
    this.responseSchemaVersion = responseSchemaVersion;
    this.completedAt = now;
    clearLease();
  }

  public void markInvalid(String failureClass, Instant now) {
    requireStatus(EnumSet.of(AiJobStatus.OUTPUT_RECEIVED, AiJobStatus.RUNNING, AiJobStatus.VALIDATED, AiJobStatus.LEASED));
    this.status = AiJobStatus.INVALID;
    this.failureClass = boundedFailure(failureClass);
    this.completedAt = now;
    clearLease();
  }

  public void fail(String failureClass, Instant now) {
    if (status.isTerminal()) {
      throw new IllegalStateException("already_terminal");
    }
    this.status = AiJobStatus.FAILED;
    this.failureClass = boundedFailure(failureClass);
    this.completedAt = now;
    clearLease();
  }

  public void scheduleRetry(String failureClass, Instant nextAttemptAt, Instant now) {
    if (status.isTerminal()) {
      throw new IllegalStateException("already_terminal");
    }
    this.failureClass = boundedFailure(failureClass);
    this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
    this.status = AiJobStatus.REQUESTED;
    clearLease();
    Objects.requireNonNull(now, "now");
  }

  public void assertLeaseOwnership(String leaseOwner, long fencingToken) {
    if (this.leaseOwner == null || !this.leaseOwner.equals(leaseOwner)) {
      throw new IllegalStateException("lease_owner_mismatch");
    }
    if (this.fencingToken != fencingToken) {
      throw new IllegalStateException("fencing_token_mismatch");
    }
  }

  public void reject(String failureClass, Instant now) {
    transition(AiJobStatus.REQUESTED, AiJobStatus.REJECTED);
    this.failureClass = boundedFailure(failureClass);
    this.completedAt = now;
    clearLease();
  }

  /**
   * Terminalize an atomically claimed (LEASED) job that cannot execute due to a
   * pre-provider condition (e.g. runtime disabled).  Must only be called by an
   * authenticated claimant that still holds the lease — lease-owner and fencing-token
   * verification must be performed by the caller before invoking this method.
   *
   * <p>Precondition: {@code status == LEASED}.
   * Forbidden from any other state.
   */
  public void failClaimed(String failureClass, Instant now) {
    if (this.status != AiJobStatus.LEASED) {
      throw new IllegalStateException("fail_claimed_requires_leased:" + this.status);
    }
    this.status = AiJobStatus.FAILED;
    this.failureClass = boundedFailure(failureClass);
    this.completedAt = Objects.requireNonNull(now, "now");
    this.nextAttemptAt = null;
    clearLease();
  }

  /** Persistence-only restore. Package-visible via adapter; not a business mutation API. */
  public void rehydrate(
      AiJobStatus status,
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
      Instant completedAt,
      UUID id,
      long rowVersion) {
    this.status = Objects.requireNonNull(status, "status");
    this.providerKey = providerKey == null ? "none" : providerKey;
    this.modelKey = modelKey == null ? "none" : modelKey;
    this.requestSchemaVersion = requestSchemaVersion;
    this.responseSchemaVersion = responseSchemaVersion;
    this.inputHash = inputHash;
    this.outputHash = outputHash;
    this.requestJson = requestJson == null || requestJson.isBlank() ? "{}" : requestJson;
    this.requestFingerprint = requestFingerprint;
    this.inputClassification = inputClassification;
    this.resultJson = resultJson == null || resultJson.isBlank() ? "{}" : resultJson;
    this.failureClass = failureClass;
    this.attemptCount = attemptCount;
    this.leaseOwner = leaseOwner;
    this.leaseUntil = leaseUntil;
    this.fencingToken = fencingToken;
    this.nextAttemptAt = nextAttemptAt;
    this.startedAt = startedAt;
    this.completedAt = completedAt;
    bindPersistence(id, rowVersion);
  }

  public void bindPersistence(UUID id, long rowVersion) {
    this.id = id;
    this.rowVersion = rowVersion;
  }

  public void assertExpectedRowVersion(long expected) {
    if (this.rowVersion != expected) {
      throw new IllegalStateException("ai_job_row_version_conflict");
    }
  }

  private void clearLease() {
    this.leaseOwner = null;
    this.leaseUntil = null;
  }

  private void transition(AiJobStatus from, AiJobStatus to) {
    if (this.status != from) {
      throw new IllegalStateException("illegal_transition:" + this.status + "->" + to);
    }
    this.status = to;
  }

  private void requireStatus(Set<AiJobStatus> allowed) {
    if (!allowed.contains(this.status)) {
      throw new IllegalStateException("illegal_status:" + this.status);
    }
  }

  private static String requireKey(String value, String field) {
    if (value == null || value.isBlank() || value.length() > 64) {
      throw new IllegalArgumentException("invalid_" + field);
    }
    return value.trim();
  }

  private static String boundedFailure(String failureClass) {
    if (failureClass == null || failureClass.isBlank()) {
      return "UNKNOWN_FAILURE";
    }
    String trimmed = failureClass.trim();
    return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
  }

  public UUID id() { return id; }
  public String publicId() { return publicId; }
  public UUID tenantId() { return tenantId; }
  public AiJobPurpose purpose() { return purpose; }
  public UUID botDefinitionVersionId() { return botDefinitionVersionId; }
  public AiJobStatus status() { return status; }
  public String providerKey() { return providerKey; }
  public String modelKey() { return modelKey; }
  public String requestSchemaVersion() { return requestSchemaVersion; }
  public String responseSchemaVersion() { return responseSchemaVersion; }
  public String inputHash() { return inputHash; }
  public String outputHash() { return outputHash; }
  public String requestJson() { return requestJson; }
  public String requestFingerprint() { return requestFingerprint; }
  public String inputClassification() { return inputClassification; }
  public String resultJson() { return resultJson; }
  public String failureClass() { return failureClass; }
  public String idempotencyKey() { return idempotencyKey; }
  public int attemptCount() { return attemptCount; }
  public String leaseOwner() { return leaseOwner; }
  public Instant leaseUntil() { return leaseUntil; }
  public long fencingToken() { return fencingToken; }
  public Instant nextAttemptAt() { return nextAttemptAt; }
  public Instant createdAt() { return createdAt; }
  public Instant startedAt() { return startedAt; }
  public Instant completedAt() { return completedAt; }
  public long rowVersion() { return rowVersion; }
}
