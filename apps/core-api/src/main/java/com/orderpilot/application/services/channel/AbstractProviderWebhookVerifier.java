package com.orderpilot.application.services.channel;

import com.orderpilot.domain.channel.ChannelConnection;
import java.util.Map;

abstract class AbstractProviderWebhookVerifier implements ChannelWebhookVerifier {
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
      if (configuredReference == null || configuredReference.isBlank()) {
        return VerificationResult.rejected("No secret configured");
      }
      // OP-CAP-42G fail-closed: the raw shared secret is intentionally not exposed in this connection-based
      // path, so the presented header value cannot be compared. A present-but-unverified secret header must
      // therefore NOT be accepted — accepting it would be a silent fail-open. Reject until real
      // provider-specific shared-secret verification is implemented.
      return VerificationResult.rejected("Shared-secret webhook verification is not implemented for this provider; failing closed");
    }
    if ("SIGNATURE_HEADER".equals(mode) || "PROVIDER_SPECIFIC".equals(mode)) {
      String signature = firstHeader(headers, "x-hub-signature-256", "x-telegram-bot-api-secret-token", "x-viber-content-signature", "x-wechat-signature");
      if (signature == null || signature.isBlank()) {
        return VerificationResult.rejected("Missing provider signature header");
      }
      // OP-CAP-42G fail-closed: provider signature verification is not implemented in this connection-based
      // path, so a present-but-unverified signature header must NOT be accepted (silent fail-open). The
      // server-owned WhatsApp HMAC path (WhatsAppSignatureVerifier) is the real verified ingress.
      return VerificationResult.rejected("Provider signature webhook verification is not implemented for this provider; failing closed");
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
