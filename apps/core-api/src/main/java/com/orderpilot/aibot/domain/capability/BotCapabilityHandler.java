package com.orderpilot.aibot.domain.capability;

public interface BotCapabilityHandler {
  String capabilityKey();

  BotCapabilityResult handle(BotCapabilityContext context);
}
