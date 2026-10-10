package com.orderpilot.aibot.application.service.handler;

import com.orderpilot.aibot.domain.capability.BotCapabilityContext;
import com.orderpilot.aibot.domain.capability.BotCapabilityHandler;
import com.orderpilot.aibot.domain.capability.BotCapabilityKey;
import com.orderpilot.aibot.domain.capability.BotCapabilityResult;
import org.springframework.stereotype.Component;

@Component
public class UnsupportedIntentHandler implements BotCapabilityHandler {
  @Override
  public String capabilityKey() {
    return BotCapabilityKey.BOT_UNSUPPORTED_RESPONSE.name();
  }

  @Override
  public BotCapabilityResult handle(BotCapabilityContext context) {
    return new BotCapabilityResult(
        "UNSUPPORTED_INTENT",
        "Я пока не умею обработать этот запрос. Могу передать его оператору.",
        true);
  }
}
