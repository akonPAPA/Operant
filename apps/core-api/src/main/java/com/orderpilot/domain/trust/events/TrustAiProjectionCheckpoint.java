package com.orderpilot.domain.trust.events;

import com.orderpilot.domain.trust.ai.AiMemorySourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-18 Trust/AI Event Projector Runtime.
 *
 * Tracks one projector's progress on one event, enforcing idempotency. Unique per
 * (tenant, projector, event) and (tenant, projector, idempotency key). A {@code COMPLETED}/{@code SKIPPED}
 * checkpoint makes re-projection a no-op. Holds the projected record reference and bounded failure
 * metadata only.
 */
@Entity
@Table(name = "trust_ai_projection_checkpoint",
    uniqueConstraints = {
        @UniqueConstraint(name = "ux_trust_ai_checkpoint_tenant_projector_event",
            columnNames = {"tenant_id", "projector_name", "event_id"}),
        @UniqueConstraint(name = "ux_trust_ai_checkpoint_tenant_projector_idem",
            columnNames = {"tenant_id", "projector_name", "idempotency_key"})})
public class TrustAiProjectionCheckpoint {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Column(name = "projector_name", nullable = false, length = 80) private String projectorName;

  @Column(name = "event_id", nullable = false) private UUID eventId;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 48) private TrustAiEventType eventType;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 32) private AiMemorySourceType sourceType;

  @Column(name = "source_id") private UUID sourceId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16) private TrustAiProjectionStatus status;

  @Column(name = "projected_record_type", length = 48) private String projectedRecordType;

  @Column(name = "projected_record_id") private UUID projectedRecordId;

  @Column(name = "idempotency_key", nullable = false, length = 160) private String idempotencyKey;

  @Column(name = "started_at", nullable = false) private Instant startedAt;

  @Column(name = "completed_at") private Instant completedAt;

  @Column(name = "failed_at") private Instant failedAt;

  @Column(name = "failure_code", length = 48) private String failureCode;

  @Column(name = "failure_message", length = 512) private String failureMessage;

  @Column(name = "attempt_count", nullable = false) private int attemptCount;

  @Column(name = "created_at", nullable = false) private Instant createdAt;

  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected TrustAiProjectionCheckpoint() {}

  public TrustAiProjectionCheckpoint(UUID tenantId, String projectorName, UUID eventId,
      TrustAiEventType eventType, AiMemorySourceType sourceType, UUID sourceId, String idempotencyKey,
      Instant now) {
    this.tenantId = tenantId;
    this.projectorName = projectorName;
    this.eventId = eventId;
    this.eventType = eventType;
    this.sourceType = sourceType;
    this.sourceId = sourceId;
    this.idempotencyKey = idempotencyKey;
    this.status = TrustAiProjectionStatus.STARTED;
    this.attemptCount = 1;
    this.startedAt = now;
    this.createdAt = now;
    this.updatedAt = now;
  }

  /** Begins a new (re)attempt. */
  public void beginAttempt(Instant now) {
    this.status = TrustAiProjectionStatus.STARTED;
    this.attemptCount += 1;
    this.startedAt = now;
    this.failedAt = null;
    this.failureCode = null;
    this.failureMessage = null;
    this.updatedAt = now;
  }

  public void complete(String projectedRecordType, UUID projectedRecordId, Instant now) {
    this.status = TrustAiProjectionStatus.COMPLETED;
    this.projectedRecordType = projectedRecordType;
    this.projectedRecordId = projectedRecordId;
    this.completedAt = now;
    this.updatedAt = now;
  }

  public void skip(Instant now) {
    this.status = TrustAiProjectionStatus.SKIPPED;
    this.completedAt = now;
    this.updatedAt = now;
  }

  public void fail(String failureCode, String failureMessage, Instant now) {
    this.status = TrustAiProjectionStatus.FAILED;
    this.failedAt = now;
    this.failureCode = failureCode;
    this.failureMessage = failureMessage;
    this.updatedAt = now;
  }

  public boolean isTerminalSuccess() {
    return status == TrustAiProjectionStatus.COMPLETED || status == TrustAiProjectionStatus.SKIPPED;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public String getProjectorName() { return projectorName; }
  public UUID getEventId() { return eventId; }
  public TrustAiEventType getEventType() { return eventType; }
  public AiMemorySourceType getSourceType() { return sourceType; }
  public UUID getSourceId() { return sourceId; }
  public TrustAiProjectionStatus getStatus() { return status; }
  public String getProjectedRecordType() { return projectedRecordType; }
  public UUID getProjectedRecordId() { return projectedRecordId; }
  public String getIdempotencyKey() { return idempotencyKey; }
  public Instant getStartedAt() { return startedAt; }
  public Instant getCompletedAt() { return completedAt; }
  public Instant getFailedAt() { return failedAt; }
  public String getFailureCode() { return failureCode; }
  public String getFailureMessage() { return failureMessage; }
  public int getAttemptCount() { return attemptCount; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
