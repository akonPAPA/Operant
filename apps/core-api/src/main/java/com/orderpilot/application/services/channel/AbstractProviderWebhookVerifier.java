package com.orderpilot.application.services.channel;

import com.orderpilot.domain.channel.ChannelConnection;
import java.util.Map;

abstract class AbstractProviderWebhookVerifier implements ChannelWebhookVerifier {
  private final WebhookVerificationAuthority authority;

  protected AbstractProviderWebhookVerifier() {
    this(WebhookVerificationAuthority.forTests(false, false));
  }

  protected AbstractProviderWebhookVerifier(WebhookVerificationAuthority authority) {
    this.authority = authority == null ? WebhookVerificationAuthority.forTests(false, false) : authority;
  }

  @Override
  public VerificationResult verify(ChannelConnection connection, Map<String, String> headers, String rawPayload) {
    String mode = connection.getWebhookVerificationMode();
    if (mode == null || mode.isBlank() || "DISABLED_FOR_LOCAL_DEV".equals(mode)) {
      if (authority.allowsUnsignedFixtureIntake()) {
        return VerificationResult.skippedLocalDev("Webhook verification disabled explicitly for local development");
      }
      return VerificationResult.rejected("Webhook verification failed");
    }
    if ("SHARED_SECRET".equals(mode)) {
      String presented = header(headers, "x-orderpilot-webhook-secret");
      if (presented == null || presented.isBlank()) {
        return VerificationResult.rejected("Missing shared secret header");
      }
      String configuredReference =
          connection.getSecretReferenceId() == null ? connection.getSecretRef() : connection.getSecretReferenceId();
      if (configuredReference == null || configuredReference.isBlank()) {
        return VerificationResult.rejected("No secret configured");
      }
      // Fail closed: raw shared secret is not exposed on the connection model for comparison.
      return VerificationResult.rejected(
          "Shared-secret webhook verification is not implemented for this provider; failing closed");
    }
    if ("SIGNATURE_HEADER".equals(mode) || "PROVIDER_SPECIFIC".equals(mode)) {
      String signature =
          firstHeader(
              headers,
              "x-hub-signature-256",
              "x-telegram-bot-api-secret-token",
              "x-viber-content-signature",
              "x-wechat-signature");
      if (signature == null || signature.isBlank()) {
        return VerificationResult.rejected("Missing provider signature header");
      }
      return VerificationResult.rejected(
          "Provider signature webhook verification is not implemented for this provider; failing closed");
    }
    return VerificationResult.rejected("Unsupported webhook verification mode");
  }

  private static String firstHeader(Map<String, String> headers, String... names) {
    for (String name : names) {
      String value = header(headers, name);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  protected static String header(Map<String, String> headers, String name) {
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
