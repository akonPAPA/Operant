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
 * A tenant-scoped, durable, internal domain event that projectors consume. It represents an approved,
 * sanitized deterministic workflow outcome (e.g. a trust risk decision, a payment update, an operator
 * correction) and points at an existing safe source record via {@code sourceType}/{@code sourceId}. It
 * stores only a bounded {@code payloadSummary} — never raw document/OCR/prompt/customer-message payload,
 * secrets, or credentials. Unique per (tenant, idempotency key) so publishing is idempotent. Status
 * transitions are encapsulated here and driven by the projector runtime.
 */
@Entity
@Table(name = "trust_ai_domain_event",
    uniqueConstraints = @UniqueConstraint(name = "ux_trust_ai_domain_event_tenant_idem",
        columnNames = {"tenant_id", "idempotency_key"}))
public class TrustAiDomainEvent {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 48) private TrustAiEventType eventType;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 32) private AiMemorySourceType sourceType;

  @Column(name = "source_id") private UUID sourceId;

  @Column(name = "subject_type", length = 32) private String subjectType;

  @Column(name = "subject_id") private UUID subjectId;

  @Column(name = "idempotency_key", nullable = false, length = 160) private String idempotencyKey;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16) private TrustAiEventStatus status;

  @Column(name = "payload_version", nullable = false) private int payloadVersion;

  /** Bounded, sanitized summary — never a raw payload. */
  @Column(name = "payload_summary", length = 512) private String payloadSummary;

  @Column(name = "occurred_at", nullable = false) private Instant occurredAt;

  @Column(name = "created_at", nullable = false) private Instant createdAt;

  @Column(name = "processed_at") private Instant processedAt;

  @Column(name = "failed_at") private Instant failedAt;

  @Column(name = "failure_code", length = 48) private String failureCode;

  @Column(name = "failure_message", length = 512) private String failureMessage;

  @Column(name = "retry_count", nullable = false) private int retryCount;

  @Column(name = "next_retry_at") private Instant nextRetryAt;

  protected TrustAiDomainEvent() {}

  public TrustAiDomainEvent(UUID tenantId, TrustAiEventType eventType, AiMemorySourceType sourceType,
      UUID sourceId, String subjectType, UUID subjectId, String idempotencyKey, int payloadVersion,
      String payloadSummary, Instant occurredAt, Instant now) {
    this.tenantId = tenantId;
    this.eventType = eventType;
    this.sourceType = sourceType;
    this.sourceId = sourceId;
    this.subjectType = subjectType;
    this.subjectId = subjectId;
    this.idempotencyKey = idempotencyKey;
    this.status = TrustAiEventStatus.PENDING;
    this.payloadVersion = payloadVersion;
    this.payloadSummary = payloadSummary;
    this.occurredAt = occurredAt;
    this.createdAt = now;
    this.retryCount = 0;
  }

  public void markProcessing() {
    this.status = TrustAiEventStatus.PROCESSING;
  }

  public void markProcessed(Instant now) {
    this.status = TrustAiEventStatus.PROCESSED;
    this.processedAt = now;
    this.failureCode = null;
    this.failureMessage = null;
    this.nextRetryAt = null;
  }

  public void markSkipped(Instant now) {
    this.status = TrustAiEventStatus.SKIPPED;
    this.processedAt = now;
    this.nextRetryAt = null;
  }

  /** Records a failed attempt and schedules a retry. Increments {@code retryCount}. */
  public void recordFailure(String failureCode, String failureMessage, Instant nextRetryAt, Instant now) {
    this.status = TrustAiEventStatus.FAILED;
    this.retryCount += 1;
    this.failedAt = now;
    this.failureCode = failureCode;
    this.failureMessage = failureMessage;
    this.nextRetryAt = nextRetryAt;
  }

  /** Terminal failure after the retry cap. Increments {@code retryCount}; no further retry is scheduled. */
  public void markDeadLettered(String failureCode, String failureMessage, Instant now) {
    this.status = TrustAiEventStatus.DEAD_LETTERED;
    this.retryCount += 1;
    this.failedAt = now;
    this.failureCode = failureCode;
    this.failureMessage = failureMessage;
    this.nextRetryAt = null;
  }

  public boolean isTerminal() {
    return status == TrustAiEventStatus.PROCESSED || status == TrustAiEventStatus.SKIPPED
        || status == TrustAiEventStatus.DEAD_LETTERED;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public TrustAiEventType getEventType() { return eventType; }
  public AiMemorySourceType getSourceType() { return sourceType; }
  public UUID getSourceId() { return sourceId; }
  public String getSubjectType() { return subjectType; }
  public UUID getSubjectId() { return subjectId; }
  public String getIdempotencyKey() { return idempotencyKey; }
  public TrustAiEventStatus getStatus() { return status; }
  public int getPayloadVersion() { return payloadVersion; }
  public String getPayloadSummary() { return payloadSummary; }
  public Instant getOccurredAt() { return occurredAt; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getProcessedAt() { return processedAt; }
  public Instant getFailedAt() { return failedAt; }
  public String getFailureCode() { return failureCode; }
  public String getFailureMessage() { return failureMessage; }
  public int getRetryCount() { return retryCount; }
  public Instant getNextRetryAt() { return nextRetryAt; }
}
