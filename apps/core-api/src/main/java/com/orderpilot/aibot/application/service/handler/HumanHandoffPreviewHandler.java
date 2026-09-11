package com.orderpilot.aibot.application.service.handler;

import com.orderpilot.aibot.domain.capability.BotCapabilityContext;
import com.orderpilot.aibot.domain.capability.BotCapabilityHandler;
import com.orderpilot.aibot.domain.capability.BotCapabilityKey;
import com.orderpilot.aibot.domain.capability.BotCapabilityResult;
import org.springframework.stereotype.Component;

@Component
public class HumanHandoffPreviewHandler implements BotCapabilityHandler {
  @Override
  public String capabilityKey() {
    return BotCapabilityKey.BOT_HUMAN_HANDOFF.name();
  }

  @Override
  public BotCapabilityResult handle(BotCapabilityContext context) {
    return new BotCapabilityResult(
        "HANDOFF_PREVIEW",
        "Передаю запрос оператору. Это предварительный handoff без создания рабочего задания.",
        true);
  }
}
