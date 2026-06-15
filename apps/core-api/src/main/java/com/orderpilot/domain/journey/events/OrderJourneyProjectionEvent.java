package com.orderpilot.domain.journey.events;

import com.orderpilot.domain.journey.JourneySourceType;
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
 * OP-CAP-23 Event/Outbox-driven Order Journey Projector Runtime.
 *
 * <p>A tenant-scoped, durable, internal domain event that the journey projector consumes. It points at an
 * existing trusted internal source row via {@code sourceType}/{@code sourceId} and carries only a bounded,
 * sanitized {@code payloadSummary} plus a {@code reasonCode} and optional correlation/causation ids — never
 * raw document/OCR/prompt/customer-message payload, payment/bank/card data, secrets, or credentials. Unique
 * per (tenant, idempotency key) so publishing is idempotent. Status transitions are encapsulated here and
 * driven by the projector runtime; there is no background daemon.
 */
@Entity
@Table(name = "order_journey_projection_event",
    uniqueConstraints = @UniqueConstraint(name = "ux_order_journey_proj_event_tenant_idem",
        columnNames = {"tenant_id", "idempotency_key"}))
public class OrderJourneyProjectionEvent {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 48) private JourneyProjectionEventType eventType;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 32) private JourneySourceType sourceType;

  @Column(name = "source_id") private UUID sourceId;

  @Column(name = "reason_code", length = 48) private String reasonCode;

  @Column(name = "correlation_id") private UUID correlationId;

  @Column(name = "causation_id") private UUID causationId;

  @Column(name = "idempotency_key", nullable = false, length = 160) private String idempotencyKey;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16) private JourneyProjectionEventStatus status;

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

  protected OrderJourneyProjectionEvent() {}

  public OrderJourneyProjectionEvent(UUID tenantId, JourneyProjectionEventType eventType,
      JourneySourceType sourceType, UUID sourceId, String reasonCode, UUID correlationId, UUID causationId,
      String idempotencyKey, int payloadVersion, String payloadSummary, Instant occurredAt, Instant now) {
    this.tenantId = tenantId;
    this.eventType = eventType;
    this.sourceType = sourceType;
    this.sourceId = sourceId;
    this.reasonCode = reasonCode;
    this.correlationId = correlationId;
    this.causationId = causationId;
    this.idempotencyKey = idempotencyKey;
    this.status = JourneyProjectionEventStatus.PENDING;
    this.payloadVersion = payloadVersion;
    this.payloadSummary = payloadSummary;
    this.occurredAt = occurredAt;
    this.createdAt = now;
    this.retryCount = 0;
  }

  public void markProcessing() {
    this.status = JourneyProjectionEventStatus.PROCESSING;
  }

  public void requeueStaleProcessingForRecovery(Instant now) {
    if (this.status == JourneyProjectionEventStatus.PROCESSING) {
      this.status = JourneyProjectionEventStatus.PENDING;
      this.nextRetryAt = null;
      this.failedAt = null;
      this.failureCode = null;
      this.failureMessage = null;
    }
  }

  public void markProcessed(Instant now) {
    this.status = JourneyProjectionEventStatus.PROCESSED;
    this.processedAt = now;
    this.failureCode = null;
    this.failureMessage = null;
    this.nextRetryAt = null;
  }

  public void markSkipped(Instant now) {
    this.status = JourneyProjectionEventStatus.SKIPPED;
    this.processedAt = now;
    this.nextRetryAt = null;
  }

  /** Records a failed attempt and schedules a retry. Increments {@code retryCount}. */
  public void recordFailure(String failureCode, String failureMessage, Instant nextRetryAt, Instant now) {
    this.status = JourneyProjectionEventStatus.FAILED;
    this.retryCount += 1;
    this.failedAt = now;
    this.failureCode = failureCode;
    this.failureMessage = failureMessage;
    this.nextRetryAt = nextRetryAt;
  }

  /** Terminal failure after the retry cap. Increments {@code retryCount}; no further retry is scheduled. */
  public void markDeadLettered(String failureCode, String failureMessage, Instant now) {
    this.status = JourneyProjectionEventStatus.DEAD_LETTERED;
    this.retryCount += 1;
    this.failedAt = now;
    this.failureCode = failureCode;
    this.failureMessage = failureMessage;
    this.nextRetryAt = null;
  }

  public boolean isTerminal() {
    return status == JourneyProjectionEventStatus.PROCESSED || status == JourneyProjectionEventStatus.SKIPPED
        || status == JourneyProjectionEventStatus.DEAD_LETTERED;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public JourneyProjectionEventType getEventType() { return eventType; }
  public JourneySourceType getSourceType() { return sourceType; }
  public UUID getSourceId() { return sourceId; }
  public String getReasonCode() { return reasonCode; }
  public UUID getCorrelationId() { return correlationId; }
  public UUID getCausationId() { return causationId; }
  public String getIdempotencyKey() { return idempotencyKey; }
  public JourneyProjectionEventStatus getStatus() { return status; }
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
