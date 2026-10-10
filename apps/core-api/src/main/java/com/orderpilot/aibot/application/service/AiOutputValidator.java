package com.orderpilot.aibot.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.aibot.domain.botdefinition.BotDefinitionConfiguration;
import com.orderpilot.aibot.domain.botdefinition.BotDefinitionValidator;
import com.orderpilot.aibot.domain.botdefinition.BotHandoffPolicy;
import com.orderpilot.aibot.domain.botdefinition.BotIntentDefinition;
import com.orderpilot.aibot.domain.botdefinition.BotIntentKey;
import com.orderpilot.aibot.domain.botdefinition.BotSecurityLinter;
import com.orderpilot.aibot.domain.capability.CapabilityRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AiOutputValidator {
  public static final String INTENT_SCHEMA_V1 = "bot-intent-classification-v1";

  private final ObjectMapper objectMapper;

  public AiOutputValidator(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public BotDefinitionConfiguration parseBotDefinition(String rawJson) {
    JsonNode root = readStrict(rawJson);
    assertNoUnknownTopLevel(
        root,
        List.of("schemaVersion", "botSummary", "intents", "handoffPolicy"));
    if (!BotDefinitionConfiguration.SCHEMA_V1.equals(root.path("schemaVersion").asText())) {
      throw new IllegalArgumentException("unknown_schema_version");
    }
    List<BotIntentDefinition> intents = new ArrayList<>();
    JsonNode intentsNode = root.path("intents");
    if (!intentsNode.isArray() || intentsNode.size() > BotDefinitionValidator.MAX_INTENTS) {
      throw new IllegalArgumentException("invalid_intents");
    }
    for (JsonNode intentNode : intentsNode) {
      assertNoUnknownTopLevel(
          intentNode,
          List.of(
              "intentKey",
              "description",
              "minimumConfidence",
              "actionKey",
              "responsePolicyKey",
              "handoffOnLowConfidence"));
      intents.add(
          new BotIntentDefinition(
              intentNode.path("intentKey").asText(),
              intentNode.path("description").asText(""),
              intentNode.path("minimumConfidence").decimalValue(),
              intentNode.path("actionKey").asText(),
              intentNode.path("responsePolicyKey").asText("SAFE_PLAIN_TEXT"),
              intentNode.path("handoffOnLowConfidence").asBoolean(true)));
    }
    JsonNode handoff = root.path("handoffPolicy");
    BotDefinitionConfiguration configuration =
        new BotDefinitionConfiguration(
            BotDefinitionConfiguration.SCHEMA_V1,
            root.path("botSummary").asText(""),
            intents,
            new BotHandoffPolicy(
                handoff.path("onUnknownIntent").asBoolean(true),
                handoff.path("onSafetyRisk").asBoolean(true),
                handoff.path("onExplicitHumanRequest").asBoolean(true)));
    BotDefinitionValidator.validate(configuration);
    BotSecurityLinter.assertClean(configuration);
    return configuration;
  }

  public IntentClassification parseIntentClassification(String rawJson) {
    JsonNode root = readStrict(rawJson);
    assertNoUnknownTopLevel(
        root,
        List.of(
            "schemaVersion",
            "intentKey",
            "confidence",
            "entities",
            "responseDraft",
            "handoffSuggested",
            "riskSignals"));
    if (!INTENT_SCHEMA_V1.equals(root.path("schemaVersion").asText())) {
      throw new IllegalArgumentException("unknown_schema_version");
    }
    String intentKey = root.path("intentKey").asText();
    if (!BotIntentKey.isKnown(intentKey)) {
      throw new IllegalArgumentException("unknown_intent_key");
    }
    BigDecimal confidence = root.path("confidence").decimalValue();
    if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException("invalid_confidence");
    }
    String draft = root.path("responseDraft").asText("");
    if (draft.length() > 1000) {
      throw new IllegalArgumentException("response_draft_too_long");
    }
    if (draft.toLowerCase(Locale.ROOT).contains("ignore previous")
        || draft.toLowerCase(Locale.ROOT).contains("system prompt")) {
      throw new IllegalArgumentException("response_draft_policy_rejected");
    }
    if (root.has("actionKey") || root.has("tenantId") || root.has("permission")) {
      throw new IllegalArgumentException("authority_field_forbidden");
    }
    return new IntentClassification(
        intentKey,
        confidence,
        Map.of(),
        draft,
        root.path("handoffSuggested").asBoolean(false),
        List.of());
  }

  private JsonNode readStrict(String rawJson) {
    if (rawJson == null || rawJson.isBlank() || rawJson.length() > 50_000) {
      throw new IllegalArgumentException("malformed_or_oversized_output");
    }
    try {
      return objectMapper.readTree(rawJson);
    } catch (Exception ex) {
      throw new IllegalArgumentException("malformed_json");
    }
  }

  private void assertNoUnknownTopLevel(JsonNode node, List<String> allowed) {
    Iterator<String> names = node.fieldNames();
    while (names.hasNext()) {
      String name = names.next();
      if (!allowed.contains(name)) {
        throw new IllegalArgumentException("unknown_field:" + name);
      }
    }
  }

  public record IntentClassification(
      String intentKey,
      BigDecimal confidence,
      Map<String, String> entities,
      String responseDraft,
      boolean handoffSuggested,
      List<String> riskSignals) {}
}
