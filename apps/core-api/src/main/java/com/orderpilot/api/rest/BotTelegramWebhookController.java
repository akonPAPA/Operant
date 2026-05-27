package com.orderpilot.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.Stage7Dtos.BotWebhookAckResponse;
import com.orderpilot.api.dto.Stage7Dtos.TelegramUpdateRequest;
import com.orderpilot.application.services.bot.BotRuntimeService;
import com.orderpilot.application.services.channel.ChannelType;
import com.orderpilot.application.services.channel.TelegramSecretTokenVerifier;
import com.orderpilot.application.services.channel.WebhookVerificationMode;
import com.orderpilot.domain.bot.BotIntent;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

@RestController
@RequestMapping({"/api/v1/bot/telegram", "/api/v1/bot-runtime/telegram"})
public class BotTelegramWebhookController {
  private final BotRuntimeService botRuntimeService;
  private final TelegramSecretTokenVerifier verifier;
  private final ObjectMapper objectMapper;

  public BotTelegramWebhookController(BotRuntimeService botRuntimeService, TelegramSecretTokenVerifier verifier, ObjectMapper objectMapper) {
    this.botRuntimeService = botRuntimeService;
    this.verifier = verifier;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/webhook")
  public BotWebhookAckResponse webhook(@RequestHeader Map<String, String> headers, @RequestBody TelegramUpdateRequest update) {
    var verification = verifier.verify(headers, update == null ? "" : objectMapper.valueToTree(update).toString(), ChannelType.TELEGRAM, null);
    if (!verification.accepted()) {
      throw new IllegalArgumentException("Telegram webhook verification failed");
    }
    if (update == null || update.message() == null) {
      return new BotWebhookAckResponse(null, null, BotIntent.UNKNOWN, "IGNORED_UNSUPPORTED_UPDATE", "Unsupported Telegram update type ignored. No business records were changed.", true, null);
    }
    if (update.message().text() == null || update.message().text().isBlank()) {
      throw new IllegalArgumentException("Telegram message text is required");
    }
    WebhookVerificationMode mode = verification.mode();
    JsonNode updateNode = objectMapper.valueToTree(update);
    return botRuntimeService.handleTelegramUpdate(updateNode, mode);
  }
}
