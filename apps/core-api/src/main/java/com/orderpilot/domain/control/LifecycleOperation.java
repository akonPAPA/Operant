package com.orderpilot.domain.control;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * P1-E2A - a durable, deployment-global lifecycle operation. This is NOT tenant-scoped: a backup is a
 * platform/deployment operation, so there is deliberately no {@code tenant_id} and no tenant/actor
 * authority on the row. The internal {@link #id} is never exposed; clients only ever see {@link #publicId}.
 *
 * <p>The row stores bounded, server-owned attribution only: an opaque public id, the fixed operation
 * type, the state/phase, a non-reversible SHA-256 fingerprint of the requesting/leasing control
 * principals, a SHA-256 hash of the idempotency key (never the raw key), an attempt counter, a
 * per-operation monotonic fencing token, lease bounds, and a bounded terminal result code. It never
 * stores a raw secret, raw principal, path, command line, environment, stdout/stderr, or customer data.
 *
 * <p>The fencing token is generated under a pessimistic row lock (see the repository/service) and strictly
 * increases each time the operation is (re-)leased, so a stale executor holding an older token is rejected
 * at completion time.
 */
@Entity
@Table(
    name = "lifecycle_operation",
    uniqueConstraints = {
        @UniqueConstraint(name = "ux_lifecycle_operation_public_id", columnNames = "public_id"),
        @UniqueConstraint(
            name = "ux_lifecycle_operation_idempotency",
            columnNames = {"operation_type", "idempotency_key_hash"})
    },
    indexes = @Index(name = "idx_lifecycle_operation_state", columnList = "state, created_at"))
public class LifecycleOperation {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "public_id", nullable = false, updatable = false, length = 40)
  private String publicId;

  @Enumerated(EnumType.STRING)
  @Column(name = "operation_type", nullable = false, updatable = false, length = 20)
  private LifecycleOperationType operationType;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 20)
  private LifecycleOperationState state;

  @Column(name = "idempotency_key_hash", nullable = false, updatable = false, length = 64)
  private String idempotencyKeyHash;

  @Column(name = "requested_by_fingerprint", nullable = false, updatable = false, length = 64)
  private String requestedByFingerprint;

  @Enumerated(EnumType.STRING)
  @Column(name = "result_code", length = 40)
  private LifecycleOperationResultCode resultCode;

  @Column(name = "attempt", nullable = false)
  private int attempt;

  @Column(name = "fencing_token")
  private Long fencingToken;

  @Column(name = "lease_expires_at")
  private Instant leaseExpiresAt;

  @Column(name = "leased_by_fingerprint", length = 64)
  private String leasedByFingerprint;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected LifecycleOperation() {
    // JPA
  }

  private LifecycleOperation(
      String publicId,
      LifecycleOperationType operationType,
      String idempotencyKeyHash,
      String requestedByFingerprint,
      Instant now) {
    this.publicId = publicId;
    this.operationType = operationType;
    this.state = LifecycleOperationState.QUEUED;
    this.idempotencyKeyHash = idempotencyKeyHash;
    this.requestedByFingerprint = requestedByFingerprint;
    this.attempt = 0;
    this.createdAt = now;
    this.updatedAt = now;
  }

  /** Creates a new QUEUED backup operation. The public id and idempotency-key hash are server-owned. */
  public static LifecycleOperation queuedBackup(
      String publicId, String idempotencyKeyHash, String requestedByFingerprint, Instant now) {
    return new LifecycleOperation(
        publicId, LifecycleOperationType.BACKUP, idempotencyKeyHash, requestedByFingerprint, now);
  }

  /** True when the operation may be (re-)leased: QUEUED, or in-flight at/after lease expiry. */
  public boolean isLeasable(Instant now) {
    if (state == LifecycleOperationState.QUEUED) {
      return true;
    }
    return state.isInFlight() && leaseExpiresAt != null && !leaseExpiresAt.isAfter(now);
  }

  /**
   * Grants a lease to the given executor: increments the attempt counter, strictly increases the fencing
   * token, and sets a bounded lease deadline. Must be called only while holding a pessimistic write lock
   * on this row so the fencing token is genuinely monotonic per operation under concurrency.
   */
  public void lease(String executorFingerprint, Instant now, Duration leaseDuration) {
    this.state = LifecycleOperationState.LEASED;
    this.attempt += 1;
    this.fencingToken = (fencingToken == null ? 0L : fencingToken) + 1L;
    this.leasedByFingerprint = executorFingerprint;
    this.leaseExpiresAt = now.plus(leaseDuration);
    this.updatedAt = now;
  }

  /** Applies a terminal outcome. The caller must have already verified owner, token, and lease validity. */
  public void complete(LifecycleOperationResultCode code, Instant now) {
    this.state = code.terminalState();
    this.resultCode = code;
    this.updatedAt = now;
  }

  public UUID getId() {
    return id;
  }

  public String getPublicId() {
    return publicId;
  }

  public LifecycleOperationType getOperationType() {
    return operationType;
  }

  public LifecycleOperationState getState() {
    return state;
  }

  public String getIdempotencyKeyHash() {
    return idempotencyKeyHash;
  }

  public String getRequestedByFingerprint() {
    return requestedByFingerprint;
  }

  public LifecycleOperationResultCode getResultCode() {
    return resultCode;
  }

  public int getAttempt() {
    return attempt;
  }

  public Long getFencingToken() {
    return fencingToken;
  }

  public Instant getLeaseExpiresAt() {
    return leaseExpiresAt;
  }

  public String getLeasedByFingerprint() {
    return leasedByFingerprint;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
