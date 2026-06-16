package com.orderpilot.domain.journey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** OP-CAP-22 — an append-only journey event (state change / signal / refresh). No raw payloads. */
@Entity
@Table(name = "order_journey_event")
public class OrderJourneyEvent {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "journey_id", nullable = false) private UUID journeyId;
  @Column(name = "event_type", nullable = false) private String eventType;
  @Column(name = "event_status") private String eventStatus;
  @Enumerated(EnumType.STRING)
  @Column(name = "evidence_level", nullable = false) private EvidenceLevel evidenceLevel;
  @Column(nullable = false) private String message;
  @Column(name = "source_type") private String sourceType;
  @Column(name = "source_ref") private String sourceRef;
  @Enumerated(EnumType.STRING)
  @Column(name = "actor_type", nullable = false) private JourneyActorType actorType;
  @Column(name = "actor_id") private UUID actorId;
  @Column(name = "customer_visible", nullable = false) private boolean customerVisible;
  @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected OrderJourneyEvent() {}

  public OrderJourneyEvent(UUID tenantId, UUID journeyId, String eventType, String eventStatus,
      EvidenceLevel evidenceLevel, String message, String sourceType, String sourceRef,
      JourneyActorType actorType, UUID actorId, boolean customerVisible, Instant occurredAt, Instant now) {
    this.tenantId = tenantId;
    this.journeyId = journeyId;
    this.eventType = eventType;
    this.eventStatus = eventStatus;
    this.evidenceLevel = evidenceLevel;
    this.message = message;
    this.sourceType = sourceType;
    this.sourceRef = sourceRef;
    this.actorType = actorType;
    this.actorId = actorId;
    this.customerVisible = customerVisible;
    this.occurredAt = occurredAt;
    this.createdAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getJourneyId() { return journeyId; }
  public String getEventType() { return eventType; }
  public String getEventStatus() { return eventStatus; }
  public EvidenceLevel getEvidenceLevel() { return evidenceLevel; }
  public String getMessage() { return message; }
  public String getSourceType() { return sourceType; }
  public String getSourceRef() { return sourceRef; }
  public JourneyActorType getActorType() { return actorType; }
  public UUID getActorId() { return actorId; }
  public boolean isCustomerVisible() { return customerVisible; }
  public Instant getOccurredAt() { return occurredAt; }
  public Instant getCreatedAt() { return createdAt; }
}
