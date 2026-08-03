package com.orderpilot.application.services.channel;

import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class TelegramSecretTokenVerifier implements WebhookSignatureVerifier {
  private final String configuredSecretToken;
  private final WebhookVerificationAuthority authority;

  public TelegramSecretTokenVerifier(String configuredSecretToken) {
    this(configuredSecretToken, WebhookVerificationAuthority.forTests(false, false));
  }

  public TelegramSecretTokenVerifier(String configuredSecretToken, WebhookVerificationAuthority authority) {
    this.configuredSecretToken = configuredSecretToken == null ? "" : configuredSecretToken;
    this.authority = authority == null ? WebhookVerificationAuthority.forTests(false, false) : authority;
  }

  @Autowired
  public TelegramSecretTokenVerifier(
      @Value("${orderpilot.bot.telegram.webhook-secret-token:}") String configuredSecretToken,
      WebhookVerificationAuthority authority,
      Environment environment) {
    this(configuredSecretToken, authority);
  }

  @Override
  public WebhookSignatureVerificationResult verify(
      Map<String, String> requestHeaders, String rawBody, ChannelType channelType, UUID tenantId) {
    if (authority.allowsUnsignedFixtureIntake() && configuredSecretToken.isBlank()) {
      return new WebhookSignatureVerificationResult(
          true,
          WebhookVerificationMode.DISABLED_FIXTURE_MODE,
          providerName(),
          "server-owned fixture mode accepted without production Telegram secret token");
    }
    if (configuredSecretToken.isBlank()) {
      return new WebhookSignatureVerificationResult(
          false, WebhookVerificationMode.FAILED, providerName(), "Telegram secret token is missing or invalid");
    }
    String presented = header(requestHeaders, "x-telegram-bot-api-secret-token");
    if (!configuredSecretToken.equals(presented)) {
      return new WebhookSignatureVerificationResult(
          false, WebhookVerificationMode.FAILED, providerName(), "Telegram secret token is missing or invalid");
    }
    return new WebhookSignatureVerificationResult(
        true, WebhookVerificationMode.PROVIDER_SPECIFIC, providerName(), "Telegram secret token accepted");
  }

  @Override
  public WebhookVerificationMode verificationMode() {
    if (!configuredSecretToken.isBlank()) {
      return WebhookVerificationMode.PROVIDER_SPECIFIC;
    }
    return authority.allowsUnsignedFixtureIntake()
        ? WebhookVerificationMode.DISABLED_FIXTURE_MODE
        : WebhookVerificationMode.FAILED;
  }

  @Override
  public String providerName() {
    return "TELEGRAM";
  }

  private static String header(Map<String, String> headers, String name) {
    if (headers == null) {
      return null;
    }
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
        return entry.getValue();
      }
    }
    return null;
  }
}
