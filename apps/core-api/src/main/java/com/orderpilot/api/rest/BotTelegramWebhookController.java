package com.orderpilot.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.orderpilot.api.dto.Stage7Dtos.BotWebhookAckResponse;
import com.orderpilot.application.services.bot.BotRuntimeService;
import com.orderpilot.application.services.channel.ChannelType;
import com.orderpilot.application.services.channel.TelegramSecretTokenVerifier;
import com.orderpilot.application.services.channel.WebhookVerificationMode;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

@RestController
@RequestMapping("/api/v1/bot/telegram")
public class BotTelegramWebhookController {
  private final BotRuntimeService botRuntimeService;
  private final TelegramSecretTokenVerifier verifier;

  public BotTelegramWebhookController(BotRuntimeService botRuntimeService, TelegramSecretTokenVerifier verifier) {
    this.botRuntimeService = botRuntimeService;
    this.verifier = verifier;
  }

  @PostMapping("/webhook")
  public BotWebhookAckResponse webhook(@RequestHeader Map<String, String> headers, @RequestBody JsonNode update) {
    var verification = verifier.verify(headers, update == null ? "" : update.toString(), ChannelType.TELEGRAM, null);
    if (!verification.accepted()) {
      throw new IllegalArgumentException("Telegram webhook verification failed");
    }
    WebhookVerificationMode mode = verification.mode();
    return botRuntimeService.handleTelegramUpdate(update, mode);
  }
}
