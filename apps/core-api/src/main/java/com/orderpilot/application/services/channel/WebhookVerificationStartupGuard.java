package com.orderpilot.application.services.channel;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fail-closed startup gate for webhook verification authority.
 *
 * <ul>
 *   <li>Production-like profiles must never enable fixture mode.
 *   <li>Production-like profiles must configure WhatsApp and Telegram verification secrets because
 *       those public provider routes remain reachable when the application is deployed.
 * </ul>
 */
@Component
public class WebhookVerificationStartupGuard implements InitializingBean {
  private final WebhookVerificationAuthority authority;
  private final String whatsAppAppSecret;
  private final String telegramSecretToken;

  public WebhookVerificationStartupGuard(
      WebhookVerificationAuthority authority,
      @Value("${orderpilot.channel-gateway.whatsapp.app-secret:}") String whatsAppAppSecret,
      @Value("${orderpilot.bot.telegram.webhook-secret-token:}") String telegramSecretToken) {
    this.authority = authority;
    this.whatsAppAppSecret = whatsAppAppSecret == null ? "" : whatsAppAppSecret;
    this.telegramSecretToken = telegramSecretToken == null ? "" : telegramSecretToken;
  }

  @Override
  public void afterPropertiesSet() {
    if (!authority.isProductionLike()) {
      return;
    }
    if (authority.isFixtureModeEnabled()) {
      throw new IllegalStateException(
          "orderpilot.webhook.fixture-mode-enabled must be false in production-like profiles");
    }
    if (whatsAppAppSecret.isBlank()) {
      throw new IllegalStateException(
          "orderpilot.channel-gateway.whatsapp.app-secret must be configured in production-like profiles");
    }
    if (telegramSecretToken.isBlank()) {
      throw new IllegalStateException(
          "orderpilot.bot.telegram.webhook-secret-token must be configured in production-like profiles");
    }
  }
}
