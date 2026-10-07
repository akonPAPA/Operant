package com.orderpilot.aibot.domain.exception;

public final class BotDefinitionNotFoundException extends RuntimeException {
  public BotDefinitionNotFoundException(String message) {
    super(message);
  }
}
