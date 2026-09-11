package com.orderpilot.aibot.domain.botdefinition;

/** Approved intent catalogue for the first M18 slice. */
public enum BotIntentKey {
  HELP_REQUEST,
  ORDER_STATUS_REQUEST,
  HUMAN_HANDOFF_REQUEST,
  UNSUPPORTED;

  public static boolean isKnown(String key) {
    if (key == null || key.isBlank()) {
      return false;
    }
    for (BotIntentKey value : values()) {
      if (value.name().equals(key)) {
        return true;
      }
    }
    return false;
  }
}
