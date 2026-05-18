package com.orderpilot.domain.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Immutable
@Table(name = "audit_event")
public class AuditEvent {
  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "actor_id")
  private UUID actorId;

  @Column(nullable = false)
  private String action;

  @Column(name = "entity_type", nullable = false)
  private String entityType;

  @Column(name = "entity_id", nullable = false)
  private String entityId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private String metadata;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  protected AuditEvent() {
  }

  public AuditEvent(UUID tenantId, UUID actorId, String action, String entityType, String entityId, String metadata, Instant occurredAt) {
    this.tenantId = tenantId;
    this.actorId = actorId;
    this.action = action;
    this.entityType = entityType;
    this.entityId = entityId;
    this.metadata = metadata;
    this.occurredAt = occurredAt;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getActorId() { return actorId; }
  public String getAction() { return action; }
  public String getEntityType() { return entityType; }
  public String getEntityId() { return entityId; }
  public String getMetadata() { return metadata; }
  public Instant getOccurredAt() { return occurredAt; }
}