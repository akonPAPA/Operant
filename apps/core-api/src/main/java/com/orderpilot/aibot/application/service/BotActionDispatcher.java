package com.orderpilot.aibot.application.service;

import com.orderpilot.aibot.domain.capability.BotCapabilityContext;
import com.orderpilot.aibot.domain.capability.BotCapabilityHandler;
import com.orderpilot.aibot.domain.capability.BotCapabilityKey;
import com.orderpilot.aibot.domain.capability.BotCapabilityResult;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class BotActionDispatcher {
  private final Map<String, BotCapabilityHandler> handlers;

  public BotActionDispatcher(List<BotCapabilityHandler> handlers) {
    this.handlers =
        handlers.stream()
            .collect(Collectors.toUnmodifiableMap(BotCapabilityHandler::capabilityKey, Function.identity()));
  }

  public BotCapabilityResult dispatch(BotCapabilityKey key, BotCapabilityContext context) {
    if (key == null) {
      throw new IllegalStateException("capability_denied");
    }
    if (context.previewMode()
        && key != BotCapabilityKey.BOT_HELP_RESPONSE
        && key != BotCapabilityKey.BOT_ORDER_STATUS_READ
        && key != BotCapabilityKey.BOT_HUMAN_HANDOFF
        && key != BotCapabilityKey.BOT_UNSUPPORTED_RESPONSE) {
      throw new IllegalStateException("capability_denied_preview");
    }
    BotCapabilityHandler handler = handlers.get(key.name());
    if (handler == null) {
      throw new IllegalStateException("capability_denied");
    }
    return handler.handle(context);
  }
}
