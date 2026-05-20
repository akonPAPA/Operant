package com.orderpilot.domain.bot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bot_rfq_request")
public class BotRfqRequest {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "conversation_id", nullable = false) private UUID conversationId;
  @Column(name = "message_id", nullable = false) private UUID messageId;
  @Column(nullable = false) private String source;
  @Column(name = "raw_text", nullable = false) private String rawText;
  @Column(name = "normalized_request_text") private String normalizedRequestText;
  @Column(nullable = false) private String status;
  @Column(name = "requires_human_review", nullable = false) private boolean requiresHumanReview;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected BotRfqRequest() {}

  public BotRfqRequest(UUID tenantId, UUID conversationId, UUID messageId, String source, String rawText, String normalizedRequestText, Instant now) {
    this.tenantId = tenantId;
    this.conversationId = conversationId;
    this.messageId = messageId;
    this.source = source;
    this.rawText = rawText;
    this.normalizedRequestText = normalizedRequestText;
    this.status = "NEEDS_HUMAN_REVIEW";
    this.requiresHumanReview = true;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getConversationId() { return conversationId; }
  public UUID getMessageId() { return messageId; }
  public String getSource() { return source; }
  public String getRawText() { return rawText; }
  public String getNormalizedRequestText() { return normalizedRequestText; }
  public String getStatus() { return status; }
  public boolean isRequiresHumanReview() { return requiresHumanReview; }
}
