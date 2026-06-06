package com.orderpilot.application.services.channel;

import com.orderpilot.domain.channel.ChannelConnection;
import java.util.Map;

abstract class AbstractProviderWebhookVerifier implements ChannelWebhookVerifier {
  private static final String[] PROVIDER_SIGNATURE_HEADER_NAMES = {
      "x-hub-signature-256",
      "x-telegram-bot-api-secret-token",
      "x-viber-content-signature",
      "x-wechat-signature"
  };

  @Override
  public VerificationResult verify(ChannelConnection connection, Map<String, String> headers, String rawPayload) {
    String mode = connection.getWebhookVerificationMode();
    if (mode == null || mode.isBlank() || "DISABLED_FOR_LOCAL_DEV".equals(mode)) {
      return VerificationResult.skippedLocalDev("Webhook verification disabled explicitly for local development");
    }
    if ("SHARED_SECRET".equals(mode)) {
      String presented = header(headers, "x-orderpilot-webhook-secret");
      if (presented == null || presented.isBlank()) {
        return VerificationResult.rejected("Missing shared secret header");
      }
      String configuredReference = connection.getSecretReferenceId() == null ? connection.getSecretRef() : connection.getSecretReferenceId();
      return configuredReference == null || configuredReference.isBlank()
          ? VerificationResult.rejected("No secret configured")
          : VerificationResult.accepted("Shared secret header present; raw secret is not exposed");
    }
    if ("SIGNATURE_HEADER".equals(mode) || "PROVIDER_SPECIFIC".equals(mode)) {
      String signature = firstHeader(headers, PROVIDER_SIGNATURE_HEADER_NAMES);
      return signature == null || signature.isBlank()
          ? VerificationResult.rejected("Missing provider signature header")
          : VerificationResult.accepted("Provider signature header present; provider adapter-ready verification stub");
    }
    return VerificationResult.rejected("Unsupported webhook verification mode");
  }

  private static String firstHeader(Map<String, String> headers, String... names) {
    for (String name : names) {
      String value = header(headers, name);
      if (value != null && !value.isBlank()) return value;
    }
    return null;
  }

  private static String header(Map<String, String> headers, String name) {
    if (headers == null) return null;
    String direct = headers.get(name);
    if (direct != null) return direct;
    return headers.get(name.toLowerCase());
  }
}
