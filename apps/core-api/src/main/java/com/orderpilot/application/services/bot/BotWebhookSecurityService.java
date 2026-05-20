package com.orderpilot.application.services.bot;

import com.orderpilot.domain.bot.BotMessageRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class BotWebhookSecurityService {
  private static final String CHANNEL_TELEGRAM = "TELEGRAM";
  private final BotMessageRepository messageRepository;

  public BotWebhookSecurityService(BotMessageRepository messageRepository) {
    this.messageRepository = messageRepository;
  }

  public void rejectReplay(UUID tenantId, String externalChatId, String externalMessageId) {
    if (messageRepository.existsByTenantIdAndChannelAndExternalChatIdAndExternalMessageId(tenantId, CHANNEL_TELEGRAM, externalChatId, externalMessageId)) {
      throw new IllegalArgumentException("Telegram message already received");
    }
  }
}
