package com.orderpilot.application.services.bot;

import com.orderpilot.domain.bot.BotResponseDraft;
import org.springframework.stereotype.Service;

@Service
public class NoopTelegramOutboundTransport implements BotOutboundTransport {
  @Override
  public StubSendResult stubSend(BotResponseDraft draft) {
    return new StubSendResult("NOOP_TELEGRAM", "STUB_SENT_NO_EXTERNAL_NETWORK", "stub-" + draft.getId());
  }
}
