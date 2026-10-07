package com.orderpilot.aibot.application.service.handler;

import com.orderpilot.aibot.domain.capability.BotCapabilityContext;
import com.orderpilot.aibot.domain.capability.BotCapabilityHandler;
import com.orderpilot.aibot.domain.capability.BotCapabilityKey;
import com.orderpilot.aibot.domain.capability.BotCapabilityResult;
import org.springframework.stereotype.Component;

/**
 * Preview-safe order-status handler. Does not invent order state and does not open a second order
 * repository. Safe public order-status query is DEFERRED for this slice.
 */
@Component
public class OrderStatusPreviewHandler implements BotCapabilityHandler {
  @Override
  public String capabilityKey() {
    return BotCapabilityKey.BOT_ORDER_STATUS_READ.name();
  }

  @Override
  public BotCapabilityResult handle(BotCapabilityContext context) {
    return new BotCapabilityResult(
        "DATA_LOOKUP_NOT_CONNECTED",
        "Я распознал запрос о статусе заказа, но источник статуса пока не подключён.",
        false);
  }
}
