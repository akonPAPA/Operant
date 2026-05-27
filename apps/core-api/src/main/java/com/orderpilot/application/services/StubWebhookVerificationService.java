package com.orderpilot.application.services;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class StubWebhookVerificationService implements WebhookVerificationService {
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
    String configuredToken = switch (provider == null ? "" : provider.toUpperCase()) {
      case "EMAIL" -> emailDevToken;
      case "TELEGRAM" -> telegramSecretToken;
      default -> "";
    };
    if (configuredToken != null && !configuredToken.isBlank()) {
      String supplied = firstHeader(headers, "x-orderpilot-webhook-token", "x-telegram-bot-api-secret-token");
      if (!configuredToken.equals(supplied)) {
        return new WebhookVerificationResult(false, "TOKEN_CONFIGURED", "Webhook token is missing or invalid");
      }
      return new WebhookVerificationResult(true, "TOKEN_MATCH", "Configured webhook token accepted");
    }
    boolean hasSignatureMaterial = headers.entrySet().stream()
        .anyMatch(entry -> entry.getKey() != null
            && entry.getKey().toLowerCase().matches(".*(signature|secret|token).*")
            && entry.getValue() != null
            && !entry.getValue().isBlank());
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
}
