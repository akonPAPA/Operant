package com.orderpilot.aibot.domain.botdefinition;

import java.math.BigDecimal;
import java.util.Objects;

/** Immutable intent binding inside a bot definition version. */
public final class BotIntentDefinition {

  private final String intentKey;
  private final String description;
  private final BigDecimal minimumConfidence;
  private final String actionKey;
  private final String responsePolicyKey;
  private final boolean handoffOnLowConfidence;

  public BotIntentDefinition(
      String intentKey,
      String description,
      BigDecimal minimumConfidence,
      String actionKey,
      String responsePolicyKey,
      boolean handoffOnLowConfidence) {
    this.intentKey = Objects.requireNonNull(intentKey, "intentKey").trim();
    this.description = description == null ? "" : description.trim();
    this.minimumConfidence = Objects.requireNonNull(minimumConfidence, "minimumConfidence");
    this.actionKey = Objects.requireNonNull(actionKey, "actionKey").trim();
    this.responsePolicyKey =
        responsePolicyKey == null || responsePolicyKey.isBlank()
            ? "SAFE_PLAIN_TEXT"
            : responsePolicyKey.trim();
    this.handoffOnLowConfidence = handoffOnLowConfidence;
  }

  public String intentKey() {
    return intentKey;
  }

  public String description() {
    return description;
  }

  public BigDecimal minimumConfidence() {
    return minimumConfidence;
  }

  public String actionKey() {
    return actionKey;
  }

  public String responsePolicyKey() {
    return responsePolicyKey;
  }

  public boolean handoffOnLowConfidence() {
    return handoffOnLowConfidence;
  }
}
