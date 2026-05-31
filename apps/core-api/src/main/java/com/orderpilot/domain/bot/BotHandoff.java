package com.orderpilot.domain.bot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "bot_handoff")
public class BotHandoff {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "conversation_id", nullable = false) private UUID conversationId;
  @Column(name = "message_id", nullable = false) private UUID messageId;
  @Column(nullable = false) private String channel;
  @Column(nullable = false) private String reason;
  @Column(name = "channel_message_id") private UUID channelMessageId;
  @Column(name = "customer_account_id") private UUID customerAccountId;
  @Column(name = "detected_intent") private String detectedIntent;
  @Column(name = "assigned_queue") private String assignedQueue;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "extracted_hints_json", columnDefinition = "jsonb") private String extractedHintsJson;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "risk_flags_json", columnDefinition = "jsonb") private String riskFlagsJson;
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
    this.assignedQueue = "BOT_REVIEW";
    this.extractedHintsJson = "{}";
    this.riskFlagsJson = "[]";
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
  public UUID getChannelMessageId() { return channelMessageId; }
  public UUID getCustomerAccountId() { return customerAccountId; }
  public String getDetectedIntent() { return detectedIntent; }
  public String getAssignedQueue() { return assignedQueue; }
  public String getExtractedHintsJson() { return extractedHintsJson; }
  public String getRiskFlagsJson() { return riskFlagsJson; }
  public String getStatus() { return status; }
  public boolean isRequiresHumanReview() { return requiresHumanReview; }
  public void attachContext(UUID channelMessageId, UUID customerAccountId, String detectedIntent, String assignedQueue, String extractedHintsJson, String riskFlagsJson, Instant now) {
    this.channelMessageId = channelMessageId;
    this.customerAccountId = customerAccountId;
    this.detectedIntent = detectedIntent;
    this.assignedQueue = assignedQueue == null || assignedQueue.isBlank() ? "BOT_REVIEW" : assignedQueue;
    this.extractedHintsJson = extractedHintsJson == null || extractedHintsJson.isBlank() ? "{}" : extractedHintsJson;
    this.riskFlagsJson = riskFlagsJson == null || riskFlagsJson.isBlank() ? "[]" : riskFlagsJson;
    this.updatedAt = now;
  }
}
