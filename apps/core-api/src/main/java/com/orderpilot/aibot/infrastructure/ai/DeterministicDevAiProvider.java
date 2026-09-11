package com.orderpilot.aibot.infrastructure.ai;

import com.orderpilot.aibot.application.port.out.AiProviderPort;
import com.orderpilot.aibot.domain.aijob.AiJobPurpose;
import com.orderpilot.aibot.domain.botdefinition.BotDefinitionConfiguration;
import com.orderpilot.aibot.domain.capability.BotCapabilityKey;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development fallback used when operant.ai.enabled=false. Returns deterministic structured JSON
 * for synthetic fixtures only — never calls a network provider.
 */
@Component
@ConditionalOnProperty(prefix = "operant.ai", name = "enabled", havingValue = "false", matchIfMissing = true)
public class DeterministicDevAiProvider implements AiProviderPort {
  @Override
  public ProviderResult generateStructured(AiProviderRequest request) {
    String text;
    if (request.purpose() == AiJobPurpose.BOT_DEFINITION_GENERATION) {
      List<String> intents = request.approvedIntentCatalogue();
      StringBuilder intentJson = new StringBuilder();
      for (int i = 0; i < intents.size(); i++) {
        String intent = intents.get(i);
        String action =
            switch (intent) {
              case "ORDER_STATUS_REQUEST" -> BotCapabilityKey.BOT_ORDER_STATUS_READ.name();
              case "HUMAN_HANDOFF_REQUEST" -> BotCapabilityKey.BOT_HUMAN_HANDOFF.name();
              default -> BotCapabilityKey.BOT_HELP_RESPONSE.name();
            };
        if (i > 0) {
          intentJson.append(',');
        }
        intentJson
            .append("{\"intentKey\":\"")
            .append(intent)
            .append("\",\"description\":\"")
            .append(intent)
            .append("\",\"minimumConfidence\":0.80,\"actionKey\":\"")
            .append(action)
            .append("\",\"responsePolicyKey\":\"SAFE_PLAIN_TEXT\",\"handoffOnLowConfidence\":true}");
      }
      text =
          "{\"schemaVersion\":\""
              + BotDefinitionConfiguration.SCHEMA_V1
              + "\",\"botSummary\":\"Deterministic draft\",\"intents\":["
              + intentJson
              + "],\"handoffPolicy\":{\"onUnknownIntent\":true,\"onSafetyRisk\":true,\"onExplicitHumanRequest\":true}}";
    } else {
      String message = request.minimizedUserContent() == null ? "" : request.minimizedUserContent().toLowerCase();
      String intent = "HELP_REQUEST";
      if (message.contains("заказ") || message.contains("order")) {
        intent = "ORDER_STATUS_REQUEST";
      } else if (message.contains("оператор") || message.contains("human") || message.contains("человек")) {
        intent = "HUMAN_HANDOFF_REQUEST";
      }
      text =
          "{\"schemaVersion\":\"bot-intent-classification-v1\",\"intentKey\":\""
              + intent
              + "\",\"confidence\":0.94,\"entities\":{},\"responseDraft\":\"Deterministic preview response.\",\"handoffSuggested\":false,\"riskSignals\":[]}";
    }
    return new ProviderResult(
        "deterministic-dev",
        "deterministic-dev-v1",
        text,
        null,
        Map.of("tokens", 0),
        Duration.ofMillis(1),
        "STOP");
  }
}
