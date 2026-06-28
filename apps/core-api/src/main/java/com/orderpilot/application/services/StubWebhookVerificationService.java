package com.orderpilot.application.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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
      @Value("${orderpilot.webhooks.dev-accept-unsigned:false}") boolean devAcceptUnsigned,
      @Value("${orderpilot.webhooks.email.dev-token:}") String emailDevToken,
      @Value("${orderpilot.webhooks.telegram.secret-token:}") String telegramSecretToken,
      Environment environment
  ) {
    this.devAcceptUnsigned = devAcceptUnsigned
        && environment != null
        && environment.acceptsProfiles(Profiles.of("local", "dev", "test"));
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
    if (devAcceptUnsigned) {
      return new WebhookVerificationResult(
          true,
          "STUB_DEV_UNSIGNED",
          "Unsigned webhook accepted only in an explicit local/dev/test profile");
    }
    return new WebhookVerificationResult(
        false,
        "VERIFICATION_NOT_CONFIGURED",
        "Webhook verification is not configured");
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

  private boolean constantTimeEquals(String expected, String supplied) {
    byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
    byte[] suppliedBytes = (supplied == null ? "" : supplied).getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(expectedBytes, suppliedBytes);
  }
}
