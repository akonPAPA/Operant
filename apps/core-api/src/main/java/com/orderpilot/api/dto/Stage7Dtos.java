package com.orderpilot.api.dto;

import com.orderpilot.domain.bot.BotIntent;
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
}
