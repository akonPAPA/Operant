package com.orderpilot.aibot.infrastructure.ai.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.orderpilot.aibot.application.port.out.AiProviderPort;
import com.orderpilot.aibot.domain.aijob.AiJobPurpose;
import com.orderpilot.aibot.infrastructure.ai.BotDefinitionPromptBuilderV1;
import com.orderpilot.aibot.infrastructure.ai.BotIntentPromptBuilderV1;
import com.orderpilot.aibot.infrastructure.configuration.GeminiProviderProperties;
import com.orderpilot.aibot.infrastructure.configuration.OperantAiProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * DEVELOPMENT_ONLY Gemini free-tier adapter. Requires operant.ai.enabled=true and a real API key.
 * Never logs headers or raw provider bodies.
 */
@Component
@ConditionalOnProperty(prefix = "operant.ai", name = "enabled", havingValue = "true")
public class GeminiFlashLiteAdapter implements AiProviderPort {
  private static final Logger log = LoggerFactory.getLogger(GeminiFlashLiteAdapter.class);

  private final OperantAiProperties aiProperties;
  private final GeminiProviderProperties geminiProperties;
  private final ObjectMapper objectMapper;
  private final RestClient restClient;

  public GeminiFlashLiteAdapter(
      OperantAiProperties aiProperties,
      GeminiProviderProperties geminiProperties,
      ObjectMapper objectMapper) {
    this.aiProperties = aiProperties;
    this.geminiProperties = geminiProperties;
    this.objectMapper = objectMapper;
    if (!geminiProperties.hasApiKey()) {
      throw new IllegalStateException("gemini_api_key_missing");
    }
    if (!aiProperties.isSyntheticOnly()
        && !"ANONYMIZED_APPROVED".equalsIgnoreCase(aiProperties.getDataPolicy())) {
      throw new IllegalStateException("unsupported_ai_data_policy");
    }
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout((int) geminiProperties.getConnectTimeout().toMillis());
    requestFactory.setReadTimeout((int) geminiProperties.getReadTimeout().toMillis());
    this.restClient =
        RestClient.builder()
            .baseUrl(trimSlash(geminiProperties.getBaseUrl()))
            .requestFactory(requestFactory)
            .build();
  }

  @Override
  public ProviderResult generateStructured(AiProviderRequest request) {
    Instant started = Instant.now();
    String system =
        request.purpose() == AiJobPurpose.BOT_DEFINITION_GENERATION
            ? BotDefinitionPromptBuilderV1.systemPolicy()
            : BotIntentPromptBuilderV1.systemPolicy();
    String user =
        request.purpose() == AiJobPurpose.BOT_DEFINITION_GENERATION
            ? BotDefinitionPromptBuilderV1.userContent(request)
            : BotIntentPromptBuilderV1.userContent(request);
    if (user.length() > aiProperties.getMaximumInputChars()) {
      throw new IllegalArgumentException("input_too_large");
    }
    ObjectNode body = objectMapper.createObjectNode();
    ArrayNode contents = body.putArray("contents");
    ObjectNode userContent = contents.addObject();
    userContent.put("role", "user");
    userContent.putArray("parts").addObject().put("text", system + "\n\n" + user);
    ObjectNode generation = body.putObject("generationConfig");
    generation.put("temperature", 0.2);
    generation.put("maxOutputTokens", request.maximumOutputTokens());
    generation.put("responseMimeType", "application/json");

    String path = "/v1beta/models/" + geminiProperties.getModel() + ":generateContent";
    try {
      String response =
          restClient
              .post()
              .uri(path)
              .contentType(MediaType.APPLICATION_JSON)
              .header("x-goog-api-key", geminiProperties.getApiKey())
              .body(body)
              .retrieve()
              .body(String.class);
      String text = extractText(response);
      return new ProviderResult(
          "gemini",
          geminiProperties.getModel(),
          text,
          null,
          Map.of(),
          Duration.between(started, Instant.now()),
          "STOP");
    } catch (RestClientResponseException ex) {
      log.warn(
          "gemini_provider_http_failure status={} correlation={}",
          ex.getStatusCode().value(),
          request.correlationReference());
      if (ex.getStatusCode().value() == 429) {
        throw new IllegalStateException("PROVIDER_RATE_LIMITED");
      }
      throw new IllegalStateException("PROVIDER_UNAVAILABLE");
    } catch (RuntimeException ex) {
      if (ex.getMessage() != null && ex.getMessage().toLowerCase().contains("timed out")) {
        throw new IllegalStateException("PROVIDER_TIMEOUT");
      }
      throw new IllegalStateException("PROVIDER_UNAVAILABLE");
    }
  }

  private String extractText(String response) {
    try {
      JsonNode root = objectMapper.readTree(response);
      JsonNode textNode =
          root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
      if (!textNode.isTextual() || textNode.asText().isBlank()) {
        throw new IllegalArgumentException("malformed_provider_output");
      }
      String text = textNode.asText();
      if (text.length() > 50_000) {
        throw new IllegalArgumentException("oversized_provider_output");
      }
      return text;
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalArgumentException("malformed_provider_output");
    }
  }

  private static String trimSlash(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      return "https://generativelanguage.googleapis.com";
    }
    return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }
}
