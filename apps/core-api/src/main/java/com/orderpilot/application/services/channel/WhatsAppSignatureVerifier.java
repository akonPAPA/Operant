package com.orderpilot.application.services.channel;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class WhatsAppSignatureVerifier implements WebhookSignatureVerifier {
  @Override
  public WebhookSignatureVerificationResult verify(Map<String, String> requestHeaders, String rawBody, ChannelType channelType, UUID tenantId) {
    String fixtureMode = requestHeaders == null ? null : requestHeaders.get("X-OrderPilot-Fixture-Mode");
    if ("true".equalsIgnoreCase(fixtureMode)) {
      return new WebhookSignatureVerificationResult(true, WebhookVerificationMode.DISABLED_FIXTURE_MODE, providerName(), "fixture mode accepted without production Meta app secret");
    }
    String required = requestHeaders == null ? null : requestHeaders.get("X-OrderPilot-Require-Signature");
    if ("true".equalsIgnoreCase(required)) {
      return new WebhookSignatureVerificationResult(false, WebhookVerificationMode.FAILED, providerName(), "production signature verification is required but not configured in Stage 10E");
    }
    return new WebhookSignatureVerificationResult(true, WebhookVerificationMode.NOT_CONFIGURED_STAGE_10E, providerName(), "WhatsApp signature verification is not configured in Stage 10E");
  }

  public boolean isVerified(String signatureHeader, String rawBody) {
    return false;
  }

  public String mode() {
    return verificationMode().name();
  }

  @Override
  public WebhookVerificationMode verificationMode() {
    return WebhookVerificationMode.NOT_CONFIGURED_STAGE_10E;
  }

  @Override
  public String providerName() {
    return "WHATSAPP";
  }
}
