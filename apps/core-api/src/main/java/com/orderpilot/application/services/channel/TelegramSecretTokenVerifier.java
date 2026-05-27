package com.orderpilot.application.services.channel;

import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TelegramSecretTokenVerifier implements WebhookSignatureVerifier {
  private final String configuredSecretToken;

  public TelegramSecretTokenVerifier(@Value("${orderpilot.bot.telegram.webhook-secret-token:}") String configuredSecretToken) {
    this.configuredSecretToken = configuredSecretToken;
  }

  @Override
  public WebhookSignatureVerificationResult verify(Map<String, String> requestHeaders, String rawBody, ChannelType channelType, UUID tenantId) {
    String mode = requestHeaders == null ? null : requestHeaders.get("X-OrderPilot-Fixture-Mode");
    if ("true".equalsIgnoreCase(mode)) {
      return new WebhookSignatureVerificationResult(true, WebhookVerificationMode.DISABLED_FIXTURE_MODE, providerName(), "fixture mode accepted without production Telegram secret token");
    }
    if (configuredSecretToken == null || configuredSecretToken.isBlank()) {
      return new WebhookSignatureVerificationResult(true, WebhookVerificationMode.NOT_CONFIGURED_STAGE_10E, providerName(), "Telegram secret token verification is not configured; local/dev unsigned webhook accepted");
    }
    String presented = header(requestHeaders, "x-telegram-bot-api-secret-token");
    if (!configuredSecretToken.equals(presented)) {
      return new WebhookSignatureVerificationResult(false, WebhookVerificationMode.FAILED, providerName(), "Telegram secret token is missing or invalid");
    }
    return new WebhookSignatureVerificationResult(true, WebhookVerificationMode.PROVIDER_SPECIFIC, providerName(), "Telegram secret token accepted");
  }

  @Override
  public WebhookVerificationMode verificationMode() {
    return WebhookVerificationMode.NOT_CONFIGURED_STAGE_10E;
  }

  @Override
  public String providerName() {
    return "TELEGRAM";
  }

  private static String header(Map<String, String> headers, String name) {
    if (headers == null) return null;
    String direct = headers.get(name);
    if (direct != null) return direct;
    return headers.get(name.toLowerCase());
  }
}
