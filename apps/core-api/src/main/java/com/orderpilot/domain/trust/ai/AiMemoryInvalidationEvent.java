package com.orderpilot.domain.trust.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-17F AI Data Runtime / Tenant-Scoped AI Memory Governance.
 *
 * Append-only, tenant-scoped trace of an {@link AiMemoryRecord} status change (invalidate / expire /
 * supersede). The record itself is preserved; this row records WHY it left {@code ACTIVE} so every
 * change is audit-explainable. Holds bounded metadata only.
 */
@Entity
@Table(name = "ai_memory_invalidation_event")
public class AiMemoryInvalidationEvent {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Column(name = "ai_memory_record_id", nullable = false) private UUID aiMemoryRecordId;

  @Enumerated(EnumType.STRING)
  @Column(name = "previous_status", nullable = false, length = 16) private AiMemoryStatus previousStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "new_status", nullable = false, length = 16) private AiMemoryStatus newStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "reason_code", nullable = false, length = 32) private AiMemoryInvalidationReasonCode reasonCode;

  @Column(name = "reason", length = 280) private String reason;

  @Enumerated(EnumType.STRING)
  @Column(name = "actor_type", nullable = false, length = 16) private AiMemoryActorType actorType;

  @Column(name = "actor_id") private UUID actorId;

  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected AiMemoryInvalidationEvent() {}

  public AiMemoryInvalidationEvent(UUID tenantId, UUID aiMemoryRecordId, AiMemoryStatus previousStatus,
      AiMemoryStatus newStatus, AiMemoryInvalidationReasonCode reasonCode, String reason,
      AiMemoryActorType actorType, UUID actorId, Instant now) {
    this.tenantId = tenantId;
    this.aiMemoryRecordId = aiMemoryRecordId;
    this.previousStatus = previousStatus;
    this.newStatus = newStatus;
    this.reasonCode = reasonCode;
    this.reason = reason;
    this.actorType = actorType;
    this.actorId = actorId;
    this.createdAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getAiMemoryRecordId() { return aiMemoryRecordId; }
  public AiMemoryStatus getPreviousStatus() { return previousStatus; }
  public AiMemoryStatus getNewStatus() { return newStatus; }
  public AiMemoryInvalidationReasonCode getReasonCode() { return reasonCode; }
  public String getReason() { return reason; }
  public AiMemoryActorType getActorType() { return actorType; }
  public UUID getActorId() { return actorId; }
  public Instant getCreatedAt() { return createdAt; }
}
