package com.orderpilot.domain.bot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bot_response_draft")
public class BotResponseDraft {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "conversation_id", nullable = false) private UUID conversationId;
  @Column(name = "source_message_id", nullable = false) private UUID sourceMessageId;
  @Column(nullable = false) private String channel;
  @Column(name = "response_type", nullable = false) private String responseType;
  @Column(name = "policy_decision", nullable = false) private String policyDecision;
  @Column(nullable = false) private String status;
  @Column(name = "response_text", nullable = false) private String responseText;
  @Column(name = "requires_operator_review", nullable = false) private boolean requiresOperatorReview;
  @Column(name = "reviewed_by") private UUID reviewedBy;
  @Column(name = "reviewed_at") private Instant reviewedAt;
  @Column(name = "stub_sent_at") private Instant stubSentAt;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected BotResponseDraft() {}

  public BotResponseDraft(UUID tenantId, UUID conversationId, UUID sourceMessageId, String channel, String responseType, String policyDecision, String responseText, boolean requiresOperatorReview, Instant now) {
    this.tenantId = tenantId;
    this.conversationId = conversationId;
    this.sourceMessageId = sourceMessageId;
    this.channel = channel;
    this.responseType = responseType;
    this.policyDecision = policyDecision;
    this.status = requiresOperatorReview ? "NEEDS_OPERATOR_REVIEW" : "DRAFTED";
    this.responseText = responseText;
    this.requiresOperatorReview = requiresOperatorReview;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void markReady(UUID reviewedBy, Instant now) {
    this.status = "READY_FOR_STUB_SEND";
    this.requiresOperatorReview = false;
    this.reviewedBy = reviewedBy;
    this.reviewedAt = now;
    this.updatedAt = now;
  }

  public void markStubSent(Instant now) {
    this.status = "STUB_SENT";
    this.stubSentAt = now;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getConversationId() { return conversationId; }
  public UUID getSourceMessageId() { return sourceMessageId; }
  public String getChannel() { return channel; }
  public String getResponseType() { return responseType; }
  public String getPolicyDecision() { return policyDecision; }
  public String getStatus() { return status; }
  public String getResponseText() { return responseText; }
  public boolean isRequiresOperatorReview() { return requiresOperatorReview; }
  public UUID getReviewedBy() { return reviewedBy; }
  public Instant getReviewedAt() { return reviewedAt; }
  public Instant getStubSentAt() { return stubSentAt; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
