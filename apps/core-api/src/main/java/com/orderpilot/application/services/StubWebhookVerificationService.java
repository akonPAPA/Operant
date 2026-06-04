package com.orderpilot.application.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class StubWebhookVerificationService implements WebhookVerificationService {
  private static final int MAX_PROVIDER_LENGTH = 64;
  private static final int MAX_PAYLOAD_LENGTH = (int) IntakeValidationService.DEFAULT_MAX_FILE_BYTES;
  private static final int MAX_HEADER_COUNT = 64;
  private static final int MAX_HEADER_NAME_LENGTH = 128;
  private static final int MAX_HEADER_VALUE_LENGTH = 4096;

  private final boolean devAcceptUnsigned;
  private final String emailDevToken;
  private final String telegramSecretToken;

  public StubWebhookVerificationService(
      @Value("${orderpilot.webhooks.dev-accept-unsigned:true}") boolean devAcceptUnsigned,
      @Value("${orderpilot.webhooks.email.dev-token:}") String emailDevToken,
      @Value("${orderpilot.webhooks.telegram.secret-token:}") String telegramSecretToken
  ) {
    this.devAcceptUnsigned = devAcceptUnsigned;
    this.emailDevToken = emailDevToken;
    this.telegramSecretToken = telegramSecretToken;
  }

  @Override
  public WebhookVerificationResult verify(String provider, String payload, Map<String, String> headers) {
    Map<String, String> safeHeaders = headers == null ? Collections.emptyMap() : headers;
    if (!withinInputLimits(provider, payload, safeHeaders)) {
      return new WebhookVerificationResult(false, "INVALID_INPUT", "Webhook verification input exceeds safe limits");
    }
    String configuredToken = switch (provider == null ? "" : provider.toUpperCase()) {
      case "EMAIL" -> emailDevToken;
      case "TELEGRAM" -> telegramSecretToken;
      default -> "";
    };
    if (configuredToken != null && !configuredToken.isBlank()) {
      String supplied = firstHeader(safeHeaders, "x-orderpilot-webhook-token", "x-telegram-bot-api-secret-token");
      if (!constantTimeEquals(configuredToken, supplied)) {
        return new WebhookVerificationResult(false, "TOKEN_CONFIGURED", "Webhook token is missing or invalid");
      }
      return new WebhookVerificationResult(true, "TOKEN_MATCH", "Configured webhook token accepted");
    }
    boolean hasSignatureMaterial = safeHeaders.entrySet().stream()
        .anyMatch(entry -> isSignatureHeaderName(entry.getKey()) && entry.getValue() != null && !entry.getValue().isBlank());
    if (hasSignatureMaterial) {
      return new WebhookVerificationResult(true, "STUB_SIGNATURE_PRESENT", "Phase 3 stub received signature material");
    }
    return new WebhookVerificationResult(devAcceptUnsigned, "STUB_DEV_UNSIGNED", "Unsigned webhook accepted only because local dev stub mode is enabled");
  }

  private String firstHeader(Map<String, String> headers, String... names) {
    for (String name : names) {
      for (var entry : headers.entrySet()) {
        if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
      }
    }
    return "";
  }

  private boolean withinInputLimits(String provider, String payload, Map<String, String> headers) {
    if (provider != null && provider.length() > MAX_PROVIDER_LENGTH) return false;
    if (payload != null && payload.length() > MAX_PAYLOAD_LENGTH) return false;
    if (headers.size() > MAX_HEADER_COUNT) return false;
    for (var entry : headers.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (key != null && key.length() > MAX_HEADER_NAME_LENGTH) return false;
      if (value != null && value.length() > MAX_HEADER_VALUE_LENGTH) return false;
    }
    return true;
  }

  private boolean isSignatureHeaderName(String name) {
    return containsIgnoreCase(name, "signature") || containsIgnoreCase(name, "secret") || containsIgnoreCase(name, "token");
  }

  private boolean containsIgnoreCase(String value, String needle) {
    if (value == null || value.length() < needle.length()) return false;
    for (int i = 0; i <= value.length() - needle.length(); i++) {
      int j = 0;
      while (j < needle.length() && Character.toLowerCase(value.charAt(i + j)) == needle.charAt(j)) j++;
      if (j == needle.length()) return true;
    }
    return false;
  }

  private boolean constantTimeEquals(String expected, String supplied) {
    byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
    byte[] suppliedBytes = (supplied == null ? "" : supplied).getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(expectedBytes, suppliedBytes);
  }
}
