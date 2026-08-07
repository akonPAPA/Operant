package com.orderpilot.aibot.domain.botdefinition;

import java.util.List;

/** Validated configuration document for one bot definition version. */
public record BotDefinitionConfiguration(
    String schemaVersion,
    String botSummary,
    List<BotIntentDefinition> intents,
    BotHandoffPolicy handoffPolicy) {

  public static final String SCHEMA_V1 = "bot-definition-proposal-v1";

  public BotDefinitionConfiguration {
    intents = intents == null ? List.of() : List.copyOf(intents);
    handoffPolicy = handoffPolicy == null ? BotHandoffPolicy.defaults() : handoffPolicy;
  }
}
