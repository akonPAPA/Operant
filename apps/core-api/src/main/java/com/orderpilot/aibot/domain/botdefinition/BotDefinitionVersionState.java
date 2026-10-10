package com.orderpilot.aibot.domain.botdefinition;

/** First-slice lifecycle for declarative bot configuration versions. */
public enum BotDefinitionVersionState {
  DRAFT,
  GENERATING,
  VALIDATING,
  VALIDATED,
  INVALID;

  public boolean isPreviewable() {
    return this == DRAFT || this == VALIDATED;
  }

  public boolean canStartGeneration() {
    return this == DRAFT || this == VALIDATED || this == INVALID;
  }
}
