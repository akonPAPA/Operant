package com.orderpilot.aibot.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.aibot.domain.botdefinition.BotDefinitionConfiguration;
import com.orderpilot.aibot.domain.botdefinition.BotHandoffPolicy;
import com.orderpilot.aibot.domain.botdefinition.BotIntentDefinition;
import com.orderpilot.aibot.domain.capability.BotCapabilityKey;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class BotConfigurationJsonMapperTest {

  private final BotConfigurationJsonMapper mapper = new BotConfigurationJsonMapper(new ObjectMapper());

  @Test
  void roundTripsTypedConfiguration() {
    BotIntentDefinition intent =
        new BotIntentDefinition(
            "HELP_REQUEST",
            "help",
            new BigDecimal("0.80"),
            BotCapabilityKey.BOT_HELP_RESPONSE.name(),
            "SAFE_PLAIN_TEXT",
            true);
    BotDefinitionConfiguration original =
        new BotDefinitionConfiguration(
            BotDefinitionConfiguration.SCHEMA_V1, "summary", List.of(intent), BotHandoffPolicy.defaults());

    BotDefinitionConfiguration restored = mapper.fromJson(mapper.toJson(original));

    assertThat(restored.schemaVersion()).isEqualTo(original.schemaVersion());
    assertThat(restored.botSummary()).isEqualTo("summary");
    assertThat(restored.intents()).hasSize(1);
    assertThat(restored.intents().getFirst().actionKey())
        .isEqualTo(BotCapabilityKey.BOT_HELP_RESPONSE.name());
    assertThat(restored.handoffPolicy().onSafetyRisk()).isTrue();
  }
}
