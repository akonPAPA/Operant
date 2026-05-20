package com.orderpilot.application.services.channel;

import java.util.Map;
import java.util.UUID;

public interface WebhookSignatureVerifier {
  WebhookSignatureVerificationResult verify(Map<String, String> requestHeaders, String rawBody, ChannelType channelType, UUID tenantId);
  WebhookVerificationMode verificationMode();
  String providerName();
}
