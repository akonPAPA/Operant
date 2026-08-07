package com.orderpilot.aibot.domain.botdefinition;

/** Handoff policy embedded in a declarative bot definition. */
public record BotHandoffPolicy(
    boolean onUnknownIntent, boolean onSafetyRisk, boolean onExplicitHumanRequest) {

  public static BotHandoffPolicy defaults() {
    return new BotHandoffPolicy(true, true, true);
  }
}
