package com.orderpilot.domain.integration;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_event")
public class OutboxEvent {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "aggregate_type", nullable = false) private String aggregateType;
  @Column(name = "aggregate_id", nullable = false) private UUID aggregateId;
  @Column(name = "event_type", nullable = false) private String eventType;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "payload_json", columnDefinition = "jsonb", nullable = false) private String payloadJson;
  @Column(nullable = false) private String status;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "published_at") private Instant publishedAt;
  @Column(name = "attempt_count", nullable = false) private int attemptCount;
  @Column(name = "last_error") private String lastError;

  protected OutboxEvent() {}

  public OutboxEvent(UUID tenantId, String aggregateType, UUID aggregateId, String eventType, String payloadJson, Instant now) {
    this.tenantId = tenantId;
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
    this.eventType = eventType;
    this.payloadJson = payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson;
    this.status = "PENDING";
    this.createdAt = now;
    this.attemptCount = 0;
  }

  public void markPublishedInternalOnly(Instant now) {
    this.status = "PUBLISHED_INTERNAL_ONLY";
    this.publishedAt = now;
  }

  public void markSkippedExternalDisabled(String reason, Instant now) {
    this.status = "SKIPPED_EXTERNAL_DISABLED";
    this.publishedAt = now;
    this.lastError = reason;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public String getAggregateType() { return aggregateType; }
  public UUID getAggregateId() { return aggregateId; }
  public String getEventType() { return eventType; }
  public String getPayloadJson() { return payloadJson; }
  public String getStatus() { return status; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getPublishedAt() { return publishedAt; }
  public int getAttemptCount() { return attemptCount; }
  public String getLastError() { return lastError; }
}
