package com.orderpilot.aibot.domain.capability;

/**
 * Backend-owned capability keys. AI output may reference only keys from {@link CapabilityRegistry}.
 */
public enum BotCapabilityKey {
  BOT_HELP_RESPONSE,
  BOT_ORDER_STATUS_READ,
  BOT_HUMAN_HANDOFF,
  BOT_UNSUPPORTED_RESPONSE;

  public static boolean isKnown(String key) {
    if (key == null || key.isBlank()) {
      return false;
    }
    for (BotCapabilityKey value : values()) {
      if (value.name().equals(key)) {
        return true;
      }
    }
    return false;
  }
}
