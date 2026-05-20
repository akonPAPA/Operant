package com.orderpilot.application.services.channel;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TelegramSecretTokenVerifier implements WebhookSignatureVerifier {
  @Override
  public WebhookSignatureVerificationResult verify(Map<String, String> requestHeaders, String rawBody, ChannelType channelType, UUID tenantId) {
    String mode = requestHeaders == null ? null : requestHeaders.get("X-OrderPilot-Fixture-Mode");
    if ("true".equalsIgnoreCase(mode)) {
      return new WebhookSignatureVerificationResult(true, WebhookVerificationMode.DISABLED_FIXTURE_MODE, providerName(), "fixture mode accepted without production Telegram secret token");
    }
    return new WebhookSignatureVerificationResult(true, WebhookVerificationMode.NOT_CONFIGURED_STAGE_10E, providerName(), "Telegram secret token verification is not configured in Stage 10E");
  }

  @Override
  public WebhookVerificationMode verificationMode() {
    return WebhookVerificationMode.NOT_CONFIGURED_STAGE_10E;
  }

  @Override
  public String providerName() {
    return "TELEGRAM";
  }
}
