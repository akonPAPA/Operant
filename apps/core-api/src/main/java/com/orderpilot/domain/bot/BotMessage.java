package com.orderpilot.domain.bot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bot_message")
public class BotMessage {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "conversation_id", nullable = false) private UUID conversationId;
  @Column(nullable = false) private String channel;
  @Column(name = "external_chat_id", nullable = false) private String externalChatId;
  @Column(name = "external_message_id", nullable = false) private String externalMessageId;
  @Column(name = "raw_text", nullable = false) private String rawText;
  @Enumerated(EnumType.STRING)
  @Column(name = "detected_intent", nullable = false) private BotIntent detectedIntent;
  @Column(nullable = false) private String status;
  @Column(name = "requires_human_review", nullable = false) private boolean requiresHumanReview;
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected BotMessage() {}

  public BotMessage(UUID tenantId, UUID conversationId, String channel, String externalChatId, String externalMessageId, String rawText, BotIntent detectedIntent, String status, boolean requiresHumanReview, Instant now) {
    this.tenantId = tenantId;
    this.conversationId = conversationId;
    this.channel = channel;
    this.externalChatId = externalChatId;
    this.externalMessageId = externalMessageId;
    this.rawText = rawText;
    this.detectedIntent = detectedIntent;
    this.status = status;
    this.requiresHumanReview = requiresHumanReview;
    this.createdAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getConversationId() { return conversationId; }
  public String getChannel() { return channel; }
  public String getExternalChatId() { return externalChatId; }
  public String getExternalMessageId() { return externalMessageId; }
  public String getRawText() { return rawText; }
  public BotIntent getDetectedIntent() { return detectedIntent; }
  public String getStatus() { return status; }
  public boolean isRequiresHumanReview() { return requiresHumanReview; }
  public Instant getCreatedAt() { return createdAt; }
}
