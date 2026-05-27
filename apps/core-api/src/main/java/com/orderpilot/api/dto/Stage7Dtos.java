package com.orderpilot.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.orderpilot.domain.bot.BotIntent;
import com.orderpilot.domain.bot.BotPolicyDecision;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Stage7Dtos {
  private Stage7Dtos() {}

  public record BotWebhookAckResponse(
      UUID conversationId,
      UUID messageId,
      BotIntent intent,
      String status,
      String responseMessage,
      boolean requiresHumanReview,
      UUID createdRfqDraftId
  ) {}
  public record BotSimulateMessageRequest(String channel, String externalChatId, String externalMessageId, String senderDisplayName, String text, boolean knownCustomerIdentity) {}
  public record BotSimulateMessageResponse(UUID conversationId, UUID messageId, BotIntent intent, BotPolicyDecision policyDecision, String reasonCode, String suggestedSafeResponse, boolean requiresHumanReview, UUID createdRfqDraftId) {}
  public record TelegramUpdateRequest(@JsonProperty("update_id") Long updateId, TelegramMessageRequest message) {}
  public record TelegramMessageRequest(@JsonProperty("message_id") Long messageId, TelegramChatRequest chat, TelegramUserRequest from, String text, Long date) {}
  public record TelegramChatRequest(String id) {}
  public record TelegramUserRequest(String id, String username, @JsonProperty("first_name") String firstName, @JsonProperty("last_name") String lastName) {}
  public record BotConversationResponse(UUID id, String channel, String externalChatId, String status, boolean requiresHumanReview, UUID linkedReviewCaseId, String policyDecision, String suggestedNextAction, Instant createdAt, Instant updatedAt) {}
  public record BotMessageResponse(UUID id, UUID conversationId, String channel, String externalChatId, String externalMessageId, String rawText, BotIntent detectedIntent, String status, boolean requiresHumanReview, Instant createdAt) {}
  public record BotHandoffResponse(UUID id, UUID conversationId, UUID messageId, String channel, String reason, String status, boolean requiresHumanReview) {}
  public record BotResponseDraftResponse(UUID id, UUID conversationId, UUID sourceMessageId, String channel, String responseType, String policyDecision, String status, String responseText, boolean requiresOperatorReview, UUID reviewedBy, Instant reviewedAt, Instant stubSentAt, Instant createdAt, Instant updatedAt) {}
  public record BotConversationDetail(BotConversationResponse conversation, List<BotMessageResponse> messages, List<BotHandoffResponse> handoffs, List<BotResponseDraftResponse> responseDrafts) {}
  public record BotReviewHandoffResponse(UUID reviewCaseId, String caseNumber, String sourceType, UUID sourceId, UUID sourceConversationId, String status, String title, String summary, boolean reusedExisting, UUID conversationId, UUID sourceMessageId, UUID rfqRequestId, String detectedIntent, String policyDecision, String latestMessage, String handoffReason, List<String> nextActions) {}
  public record CreateHandoffRequest(UUID messageId, String reason) {}
  public record LinkReviewCaseRequest(UUID reviewCaseId) {}
  public record CreateBotResponseDraftRequest(UUID sourceMessageId, boolean knownCustomerIdentity) {}
  public record MarkBotResponseReadyRequest(UUID reviewedBy) {}
}
