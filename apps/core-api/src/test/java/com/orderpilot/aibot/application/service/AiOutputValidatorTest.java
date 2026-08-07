package com.orderpilot.aibot.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.aibot.domain.botdefinition.BotDefinitionConfiguration;
import org.junit.jupiter.api.Test;

class AiOutputValidatorTest {
  private final AiOutputValidator validator = new AiOutputValidator(new ObjectMapper());

  @Test
  void parsesValidBotDefinition() {
    String json =
        """
        {"schemaVersion":"bot-definition-proposal-v1","botSummary":"Help bot","intents":[
          {"intentKey":"HELP_REQUEST","description":"help","minimumConfidence":0.8,
           "actionKey":"BOT_HELP_RESPONSE","responsePolicyKey":"SAFE_PLAIN_TEXT","handoffOnLowConfidence":true}
        ],"handoffPolicy":{"onUnknownIntent":true,"onSafetyRisk":true,"onExplicitHumanRequest":true}}
        """;
    BotDefinitionConfiguration config = validator.parseBotDefinition(json);
    assertThat(config.intents()).hasSize(1);
  }

  @Test
  void rejectsHostileCapability() {
    String json =
        """
        {"schemaVersion":"bot-definition-proposal-v1","botSummary":"x","intents":[
          {"intentKey":"HELP_REQUEST","description":"help","minimumConfidence":0.8,
           "actionKey":"BOT_ARBITRARY_ERP_WRITE","responsePolicyKey":"SAFE_PLAIN_TEXT","handoffOnLowConfidence":true}
        ],"handoffPolicy":{"onUnknownIntent":true,"onSafetyRisk":true,"onExplicitHumanRequest":true}}
        """;
    assertThatThrownBy(() -> validator.parseBotDefinition(json))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMalformedJson() {
    assertThatThrownBy(() -> validator.parseBotDefinition("{not-json"))
        .hasMessageContaining("malformed_json");
  }
}
