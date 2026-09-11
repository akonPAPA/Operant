package com.orderpilot.aibot.domain.botdefinition;

/** Optimistic / expected-version conflict for bot definition aggregates. */
public final class BotDefinitionVersionConflictException extends RuntimeException {
  public BotDefinitionVersionConflictException(String message) {
    super(message);
  }
}
