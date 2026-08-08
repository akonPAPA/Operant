package com.orderpilot.aibot.application.service.handler;

import com.orderpilot.aibot.domain.capability.BotCapabilityContext;
import com.orderpilot.aibot.domain.capability.BotCapabilityHandler;
import com.orderpilot.aibot.domain.capability.BotCapabilityKey;
import com.orderpilot.aibot.domain.capability.BotCapabilityResult;
import org.springframework.stereotype.Component;

@Component
public class HelpResponseHandler implements BotCapabilityHandler {
  @Override
  public String capabilityKey() {
    return BotCapabilityKey.BOT_HELP_RESPONSE.name();
  }

  @Override
  public BotCapabilityResult handle(BotCapabilityContext context) {
    return new BotCapabilityResult(
        "HELP_RESPONSE",
        "Я могу помочь с общими вопросами. Для статуса заказа укажите номер, или попросите оператора.",
        false);
  }
}
