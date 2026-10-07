package com.orderpilot.aibot.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.orderpilot.aibot.domain.botdefinition.BotDefinitionConfiguration;
import com.orderpilot.aibot.domain.botdefinition.BotHandoffPolicy;
import com.orderpilot.aibot.domain.botdefinition.BotIntentDefinition;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class BotConfigurationJsonMapper {
  private final ObjectMapper objectMapper;

  public BotConfigurationJsonMapper(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public String toJson(BotDefinitionConfiguration configuration) {
    try {
      ObjectNode root = objectMapper.createObjectNode();
      root.put("schemaVersion", configuration.schemaVersion());
      root.put("botSummary", configuration.botSummary() == null ? "" : configuration.botSummary());
      ArrayNode intents = root.putArray("intents");
      for (BotIntentDefinition intent : configuration.intents()) {
        ObjectNode node = intents.addObject();
        node.put("intentKey", intent.intentKey());
        node.put("description", intent.description());
        node.put("minimumConfidence", intent.minimumConfidence());
        node.put("actionKey", intent.actionKey());
        node.put("responsePolicyKey", intent.responsePolicyKey());
        node.put("handoffOnLowConfidence", intent.handoffOnLowConfidence());
      }
      ObjectNode handoff = root.putObject("handoffPolicy");
      BotHandoffPolicy policy = configuration.handoffPolicy();
      handoff.put("onUnknownIntent", policy.onUnknownIntent());
      handoff.put("onSafetyRisk", policy.onSafetyRisk());
      handoff.put("onExplicitHumanRequest", policy.onExplicitHumanRequest());
      return objectMapper.writeValueAsString(root);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("configuration_serialize_failed", ex);
    }
  }

  /**
   * Empty JSON is allowed only for newly created DRAFT shells.
   * Any persisted non-empty configuration is parsed strictly.
   */
  public BotDefinitionConfiguration fromJson(String json) {
    try {
      if (json == null || json.isBlank()) {
        return emptyDraft();
      }
      JsonNode root = objectMapper.readTree(json);
      if (!root.isObject()) {
        throw new IllegalStateException("configuration_root_must_be_object");
      }
      if (root.isEmpty()) {
        return emptyDraft();
      }
      if (!root.hasNonNull("schemaVersion")) {
        throw new IllegalStateException("configuration_schema_version_required");
      }
      String schema = root.get("schemaVersion").asText();
      if (!BotDefinitionConfiguration.SCHEMA_V1.equals(schema)) {
        throw new IllegalStateException("configuration_unknown_schema");
      }
      if (!root.has("botSummary") || !root.get("botSummary").isTextual()) {
        throw new IllegalStateException("configuration_bot_summary_required");
      }
      if (!root.has("intents") || !root.get("intents").isArray()) {
        throw new IllegalStateException("configuration_intents_required");
      }
      if (!root.has("handoffPolicy") || !root.get("handoffPolicy").isObject()) {
        throw new IllegalStateException("configuration_handoff_policy_required");
      }
      rejectUnknownRootFields(root);
      List<BotIntentDefinition> intents = new ArrayList<>();
      for (JsonNode intentNode : root.get("intents")) {
        if (!intentNode.isObject()) {
          throw new IllegalStateException("configuration_intent_must_be_object");
        }
        requireText(intentNode, "intentKey");
        requireText(intentNode, "description");
        requireText(intentNode, "actionKey");
        requireText(intentNode, "responsePolicyKey");
        if (!intentNode.has("minimumConfidence") || !intentNode.get("minimumConfidence").isNumber()) {
          throw new IllegalStateException("configuration_minimum_confidence_required");
        }
        if (!intentNode.has("handoffOnLowConfidence") || !intentNode.get("handoffOnLowConfidence").isBoolean()) {
          throw new IllegalStateException("configuration_handoff_flag_required");
        }
        intents.add(
            new BotIntentDefinition(
                intentNode.get("intentKey").asText(),
                intentNode.get("description").asText(),
                intentNode.get("minimumConfidence").decimalValue(),
                intentNode.get("actionKey").asText(),
                intentNode.get("responsePolicyKey").asText(),
                intentNode.get("handoffOnLowConfidence").asBoolean()));
      }
      JsonNode handoff = root.get("handoffPolicy");
      requireBoolean(handoff, "onUnknownIntent");
      requireBoolean(handoff, "onSafetyRisk");
      requireBoolean(handoff, "onExplicitHumanRequest");
      BotHandoffPolicy policy =
          new BotHandoffPolicy(
              handoff.get("onUnknownIntent").asBoolean(),
              handoff.get("onSafetyRisk").asBoolean(),
              handoff.get("onExplicitHumanRequest").asBoolean());
      return new BotDefinitionConfiguration(
          schema, root.get("botSummary").asText(), intents, policy);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("configuration_deserialize_failed", ex);
    }
  }

  private static BotDefinitionConfiguration emptyDraft() {
    return new BotDefinitionConfiguration(
        BotDefinitionConfiguration.SCHEMA_V1, "", List.of(), BotHandoffPolicy.defaults());
  }

  private static void rejectUnknownRootFields(JsonNode root) {
    Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
    while (fields.hasNext()) {
      String name = fields.next().getKey();
      if (!name.equals("schemaVersion")
          && !name.equals("botSummary")
          && !name.equals("intents")
          && !name.equals("handoffPolicy")) {
        throw new IllegalStateException("configuration_unknown_field:" + name);
      }
    }
  }

  private static void requireText(JsonNode node, String field) {
    if (!node.has(field) || !node.get(field).isTextual() || node.get(field).asText().isBlank()) {
      throw new IllegalStateException("configuration_" + field + "_required");
    }
  }

  private static void requireBoolean(JsonNode node, String field) {
    if (!node.has(field) || !node.get(field).isBoolean()) {
      throw new IllegalStateException("configuration_" + field + "_required");
    }
  }
}
