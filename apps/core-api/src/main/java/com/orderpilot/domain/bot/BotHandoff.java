package com.orderpilot.domain.bot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bot_handoff")
public class BotHandoff {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "conversation_id", nullable = false) private UUID conversationId;
  @Column(name = "message_id", nullable = false) private UUID messageId;
  @Column(nullable = false) private String channel;
  @Column(nullable = false) private String reason;
  @Column(nullable = false) private String status;
  @Column(name = "requires_human_review", nullable = false) private boolean requiresHumanReview;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected BotHandoff() {}

  public BotHandoff(UUID tenantId, UUID conversationId, UUID messageId, String channel, String reason, Instant now) {
    this.tenantId = tenantId;
    this.conversationId = conversationId;
    this.messageId = messageId;
    this.channel = channel;
    this.reason = reason;
    this.status = "OPEN";
    this.requiresHumanReview = true;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getConversationId() { return conversationId; }
  public UUID getMessageId() { return messageId; }
  public String getChannel() { return channel; }
  public String getReason() { return reason; }
  public String getStatus() { return status; }
  public boolean isRequiresHumanReview() { return requiresHumanReview; }
}
