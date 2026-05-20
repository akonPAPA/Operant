package com.orderpilot.api.dto;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class Stage10DOmnichannelDtos {
  private Stage10DOmnichannelDtos() {}

  public record ChannelGatewayMessageRequest(
      String channelType,
      String externalMessageId,
      String externalConversationId,
      String externalSenderId,
      String senderDisplayName,
      String senderPhone,
      String rawText,
      List<String> attachmentRefs,
      String rawPayloadJson,
      String idempotencyKey) {}

  public record ChannelGatewayMessageResponse(
      UUID id,
      String channel,
      String externalMessageId,
      String conversationId,
      String senderHandle,
      String messageType,
      String textContent,
      String status,
      UUID channelIdentityId,
      UUID customerAccountId,
      UUID customerContactId,
      String signatureVerificationMode,
      Instant receivedAt) {}

  public record ChannelGatewayAckResponse(
      String status,
      int acceptedCount,
      boolean signatureVerified,
      String signatureMode,
      List<ChannelGatewayMessageResponse> messages) {}

  public record ChannelIdentityResponse(
      UUID id,
      String channelType,
      String externalSenderId,
      String externalConversationId,
      String senderPhone,
      String senderDisplayName,
      UUID customerAccountId,
      UUID customerContactId,
      String identityStatus,
      BigDecimal matchConfidence,
      Instant createdAt,
      Instant updatedAt,
      Instant linkedAt,
      UUID linkedByUserId,
      String notes) {}

  public record ChannelIdentityLinkRequest(
      UUID customerAccountId,
      UUID customerContactId,
      UUID linkedByUserId,
      String notes) {}

  public record ChannelIdentityActionRequest(String notes) {}
}
