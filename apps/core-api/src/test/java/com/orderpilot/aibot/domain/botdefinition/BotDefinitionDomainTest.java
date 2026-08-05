package com.orderpilot.aibot.domain.botdefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.aibot.domain.capability.BotCapabilityKey;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BotDefinitionDomainTest {

  @Test
  void validDraftIsCreated() {
    Instant now = Instant.parse("2026-08-04T12:00:00Z");
    BotDefinition bot =
        new BotDefinition("bot_pub_1", UUID.randomUUID(), "Order Assistant", "Helps with orders", UUID.randomUUID(), now);
    BotDefinitionVersion version =
        new BotDefinitionVersion("botver_1", bot.tenantId(), UUID.randomUUID(), 1, now);

    assertThat(bot.name()).isEqualTo("Order Assistant");
    assertThat(version.state()).isEqualTo(BotDefinitionVersionState.DRAFT);
    assertThat(version.state().isPreviewable()).isTrue();
  }

  @Test
  void invalidConfidenceIsRejected() {
    BotIntentDefinition intent =
        new BotIntentDefinition(
            "HELP_REQUEST",
            "help",
            new BigDecimal("1.5"),
            BotCapabilityKey.BOT_HELP_RESPONSE.name(),
            "SAFE_PLAIN_TEXT",
            true);
    BotDefinitionConfiguration config =
        new BotDefinitionConfiguration(
            BotDefinitionConfiguration.SCHEMA_V1, "summary", List.of(intent), BotHandoffPolicy.defaults());

    assertThatThrownBy(() -> BotDefinitionValidator.validate(config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid_confidence");
  }

  @Test
  void duplicateIntentIsRejected() {
    BotIntentDefinition a =
        new BotIntentDefinition(
            "HELP_REQUEST", "a", new BigDecimal("0.8"), BotCapabilityKey.BOT_HELP_RESPONSE.name(), "SAFE_PLAIN_TEXT", true);
    BotIntentDefinition b =
        new BotIntentDefinition(
            "HELP_REQUEST", "b", new BigDecimal("0.8"), BotCapabilityKey.BOT_HELP_RESPONSE.name(), "SAFE_PLAIN_TEXT", true);
    BotDefinitionConfiguration config =
        new BotDefinitionConfiguration(
            BotDefinitionConfiguration.SCHEMA_V1, "summary", List.of(a, b), BotHandoffPolicy.defaults());

    assertThatThrownBy(() -> BotDefinitionValidator.validate(config))
        .hasMessageContaining("duplicate_intent_key");
  }

  @Test
  void unknownActionKeyIsRejected() {
    BotIntentDefinition intent =
        new BotIntentDefinition(
            "HELP_REQUEST", "a", new BigDecimal("0.8"), "DELETE_EVERYTHING", "SAFE_PLAIN_TEXT", true);
    BotDefinitionConfiguration config =
        new BotDefinitionConfiguration(
            BotDefinitionConfiguration.SCHEMA_V1, "summary", List.of(intent), BotHandoffPolicy.defaults());

    assertThatThrownBy(() -> BotDefinitionValidator.validate(config))
        .hasMessageContaining("unknown_action_key");
  }

  @Test
  void sqlAndUrlCapabilityIsRejected() {
    BotIntentDefinition intent =
        new BotIntentDefinition(
            "HELP_REQUEST",
            "run DROP TABLE users; see https://evil.example",
            new BigDecimal("0.8"),
            BotCapabilityKey.BOT_HELP_RESPONSE.name(),
            "SAFE_PLAIN_TEXT",
            true);
    BotDefinitionConfiguration config =
        new BotDefinitionConfiguration(
            BotDefinitionConfiguration.SCHEMA_V1, "summary", List.of(intent), BotHandoffPolicy.defaults());

    assertThatThrownBy(() -> BotDefinitionValidator.validate(config))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aiCannotAddCapabilityOutsideRegistry() {
    assertThat(
            com.orderpilot.aibot.domain.capability.CapabilityRegistry.isAllowed(
                "BOT_ARBITRARY_ERP_WRITE"))
        .isFalse();
  }

  @Test
  void stateTransitionFollowsLegalPath() {
    Instant now = Instant.parse("2026-08-04T12:00:00Z");
    BotDefinitionVersion version =
        new BotDefinitionVersion("botver_1", UUID.randomUUID(), UUID.randomUUID(), 1, now);
    version.markGenerating(now);
    assertThat(version.state()).isEqualTo(BotDefinitionVersionState.GENERATING);

    BotIntentDefinition intent =
        new BotIntentDefinition(
            "HELP_REQUEST",
            "help",
            new BigDecimal("0.80"),
            BotCapabilityKey.BOT_HELP_RESPONSE.name(),
            "SAFE_PLAIN_TEXT",
            true);
    BotDefinitionConfiguration config =
        new BotDefinitionConfiguration(
            BotDefinitionConfiguration.SCHEMA_V1, "Help bot", List.of(intent), BotHandoffPolicy.defaults());
    version.applyValidatedConfiguration(config, "{\"ok\":true}", "{\"provider\":\"gemini\"}", now);

    assertThat(version.state()).isEqualTo(BotDefinitionVersionState.VALIDATED);
  }

  @Test
  void optimisticVersionConflictFails() {
    Instant now = Instant.parse("2026-08-04T12:00:00Z");
    BotDefinition bot =
        new BotDefinition("bot_pub_1", UUID.randomUUID(), "Order Assistant", "d", UUID.randomUUID(), now);
    bot.bindPersistence(UUID.randomUUID(), 2L);
    assertThatThrownBy(() -> bot.assertExpectedRowVersion(1L))
        .isInstanceOf(BotDefinitionVersionConflictException.class);
  }
}
