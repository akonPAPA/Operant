package com.orderpilot.application.services.channel;

import com.orderpilot.domain.channel.ChannelConnection;
import com.orderpilot.domain.channel.ChannelProviderType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Connection-scoped WhatsApp webhook verifier. Mirrors {@link MetaMessengerWebhookVerifier}: when the
 * server-configured Meta app secret is present and the connection enforces signature mode, verifies
 * {@code X-Hub-Signature-256} over the exact raw body. Otherwise defers to the shared fail-closed
 * {@link AbstractProviderWebhookVerifier} contract (local-dev skip only under server-owned fixture
 * authority).
 */
@Component
public class WhatsAppWebhookVerifier extends AbstractProviderWebhookVerifier {
  private static final String SIGNATURE_HEADER = "x-hub-signature-256";
  private static final String SIGNATURE_PREFIX = "sha256=";

  private final String appSecret;

  public WhatsAppWebhookVerifier() {
    this("", WebhookVerificationAuthority.forTests(false, false));
  }

  public WhatsAppWebhookVerifier(String appSecret, WebhookVerificationAuthority authority) {
    super(authority);
    this.appSecret = appSecret == null ? "" : appSecret;
  }

  @Autowired
  public WhatsAppWebhookVerifier(
      @Value("${orderpilot.channel-gateway.whatsapp.app-secret:}") String appSecret,
      WebhookVerificationAuthority authority,
      org.springframework.core.env.Environment environment) {
    this(appSecret, authority);
  }

  @Override
  public ChannelProviderType providerType() {
    return ChannelProviderType.WHATSAPP;
  }

  @Override
  public VerificationResult verify(ChannelConnection connection, Map<String, String> headers, String rawPayload) {
    String mode = connection.getWebhookVerificationMode();
    boolean enforcing = "SIGNATURE_HEADER".equals(mode) || "PROVIDER_SPECIFIC".equals(mode);
    if (enforcing && !appSecret.isBlank()) {
      String presented = header(headers, SIGNATURE_HEADER);
      if (presented == null || presented.isBlank()) {
        return VerificationResult.rejected("Missing provider signature header");
      }
      if (!signatureMatches(presented, rawPayload)) {
        return VerificationResult.rejected("WhatsApp webhook signature verification failed");
      }
      return VerificationResult.configuredVerifyOnly("WhatsApp webhook signature verified");
    }
    return super.verify(connection, headers, rawPayload);
  }

  private boolean signatureMatches(String presentedHeader, String rawPayload) {
    String expected = computeHmacSha256Hex(rawPayload == null ? "" : rawPayload);
    String presented =
        presentedHeader.startsWith(SIGNATURE_PREFIX)
            ? presentedHeader.substring(SIGNATURE_PREFIX.length())
            : presentedHeader;
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
  }

  private String computeHmacSha256Hex(String rawPayload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(rawPayload.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        builder.append(String.format("%02x", b & 0xff));
      }
      return builder.toString();
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to compute webhook signature");
    }
  }
}
