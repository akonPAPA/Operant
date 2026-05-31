package com.orderpilot.domain.bot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bot_rate_limit_event")
public class BotRateLimitEvent {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "conversation_key", nullable = false) private String conversationKey;
  @Column(name = "event_type", nullable = false) private String eventType;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  protected BotRateLimitEvent() {}
  public BotRateLimitEvent(UUID tenantId, String conversationKey, String eventType, Instant now) { this.tenantId = tenantId; this.conversationKey = conversationKey; this.eventType = eventType; this.createdAt = now; }
  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public String getConversationKey() { return conversationKey; }
  public String getEventType() { return eventType; }
  public Instant getCreatedAt() { return createdAt; }
}
