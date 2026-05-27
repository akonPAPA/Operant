package com.orderpilot.application.services.bot;

import com.orderpilot.domain.bot.BotResponseDraft;

public interface BotOutboundTransport {
  StubSendResult stubSend(BotResponseDraft draft);

  record StubSendResult(String transport, String status, String externalDeliveryId) {}
}
