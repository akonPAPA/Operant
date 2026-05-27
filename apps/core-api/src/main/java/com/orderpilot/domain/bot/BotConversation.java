package com.orderpilot.domain.bot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bot_conversation")
public class BotConversation {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(nullable = false) private String channel;
  @Column(name = "external_chat_id", nullable = false) private String externalChatId;
  @Column(nullable = false) private String status;
  @Column(name = "requires_human_review", nullable = false) private boolean requiresHumanReview;
  @Column(name = "linked_review_case_id") private UUID linkedReviewCaseId;
  @Column(name = "policy_decision") private String policyDecision;
  @Column(name = "suggested_next_action") private String suggestedNextAction;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected BotConversation() {}

  public BotConversation(UUID tenantId, String channel, String externalChatId, Instant now) {
    this.tenantId = tenantId;
    this.channel = channel;
    this.externalChatId = externalChatId;
    this.status = "OPEN";
    this.requiresHumanReview = false;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void touch(String status, boolean requiresHumanReview, Instant now) {
    this.status = status;
    this.requiresHumanReview = requiresHumanReview;
    this.updatedAt = now;
  }
  public void applyPolicy(String policyDecision, String suggestedNextAction, Instant now) { this.policyDecision = policyDecision; this.suggestedNextAction = suggestedNextAction; this.updatedAt = now; }
  public void linkReviewCase(UUID reviewCaseId, Instant now) { this.linkedReviewCaseId = reviewCaseId; this.updatedAt = now; }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public String getChannel() { return channel; }
  public String getExternalChatId() { return externalChatId; }
  public String getStatus() { return status; }
  public boolean isRequiresHumanReview() { return requiresHumanReview; }
  public UUID getLinkedReviewCaseId() { return linkedReviewCaseId; }
  public String getPolicyDecision() { return policyDecision; }
  public String getSuggestedNextAction() { return suggestedNextAction; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
