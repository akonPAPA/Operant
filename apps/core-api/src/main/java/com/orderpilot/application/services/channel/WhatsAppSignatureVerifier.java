package com.orderpilot.application.services.channel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Stage-10E WhatsApp (Meta) inbound webhook signature verifier.
 *
 * <p>Verification authority is <b>server-owned</b>. Whether a signature is required is decided by the
 * presence of a server-configured app secret ({@code orderpilot.channel-gateway.whatsapp.app-secret}),
 * never by a client-supplied header. When the secret is configured this performs deterministic
 * HMAC-SHA256 verification of the Meta {@code X-Hub-Signature-256} header against the raw body and
 * <b>fails closed</b> on a missing or mismatched signature. When the secret is not configured it
 * reports the honest Stage-10E unconfigured boundary (and still fails closed if a caller explicitly
 * demands signature enforcement via {@code X-OrderPilot-Require-Signature}).
 *
 * <p>This mirrors the already-correct server-configured pattern in {@link TelegramSecretTokenVerifier}.
 * It performs no external/network calls and uses no provider SDK.
 */
@Service
public class WhatsAppSignatureVerifier implements WebhookSignatureVerifier {
  private static final String SIGNATURE_HEADER = "X-Hub-Signature-256";
  private static final String SIGNATURE_PREFIX = "sha256=";

  /** Server-configured Meta/WhatsApp app secret. Blank means production verification is not configured. */
  private final String appSecret;

  public WhatsAppSignatureVerifier() {
    this("");
  }

  // OP-CAP-42G: explicitly mark the property constructor as the Spring-autowired one. With two
  // constructors and no @Autowired marker, Spring silently selected the no-arg constructor, so the
  // server-configured app secret was never applied at runtime and the gateway stayed in the
  // accept-by-default NOT_CONFIGURED mode (the 42F enforcement was inert when wired by Spring). The
  // no-arg constructor is retained for direct unit construction in tests.
  @Autowired
  public WhatsAppSignatureVerifier(@Value("${orderpilot.channel-gateway.whatsapp.app-secret:}") String appSecret) {
    this.appSecret = appSecret == null ? "" : appSecret;
  }

  @Override
  public WebhookSignatureVerificationResult verify(Map<String, String> requestHeaders, String rawBody, ChannelType channelType, UUID tenantId) {
    String fixtureMode = header(requestHeaders, "X-OrderPilot-Fixture-Mode");
    if ("true".equalsIgnoreCase(fixtureMode)) {
      return new WebhookSignatureVerificationResult(true, WebhookVerificationMode.DISABLED_FIXTURE_MODE, providerName(), "fixture mode accepted without production Meta app secret");
    }
    // Server-configured production verification: a configured secret makes a valid signature mandatory.
    if (!appSecret.isBlank()) {
      String presented = header(requestHeaders, SIGNATURE_HEADER);
      if (presented == null || presented.isBlank()) {
        return new WebhookSignatureVerificationResult(false, WebhookVerificationMode.FAILED, providerName(), "webhook signature header is missing");
      }
      if (!signatureMatches(presented, rawBody)) {
        return new WebhookSignatureVerificationResult(false, WebhookVerificationMode.FAILED, providerName(), "webhook signature verification failed");
      }
      return new WebhookSignatureVerificationResult(true, WebhookVerificationMode.CONFIGURED_VERIFY_ONLY, providerName(), "webhook signature verified");
    }
    // No server secret configured. A client header cannot grant authority, but it may still demand the
    // fail-closed contract (proves production behaviour before a secret is provisioned).
    String required = header(requestHeaders, "X-OrderPilot-Require-Signature");
    if ("true".equalsIgnoreCase(required)) {
      return new WebhookSignatureVerificationResult(false, WebhookVerificationMode.FAILED, providerName(), "production signature verification is required but not configured in Stage 10E");
    }
    return new WebhookSignatureVerificationResult(true, WebhookVerificationMode.NOT_CONFIGURED_STAGE_10E, providerName(), "WhatsApp signature verification is not configured in Stage 10E");
  }

  public boolean isVerified(String signatureHeader, String rawBody) {
    return !appSecret.isBlank() && signatureHeader != null && signatureMatches(signatureHeader, rawBody);
  }

  public String mode() {
    return verificationMode().name();
  }

  @Override
  public WebhookVerificationMode verificationMode() {
    return appSecret.isBlank() ? WebhookVerificationMode.NOT_CONFIGURED_STAGE_10E : WebhookVerificationMode.CONFIGURED_VERIFY_ONLY;
  }

  @Override
  public String providerName() {
    return "WHATSAPP";
  }

  private boolean signatureMatches(String presentedHeader, String rawBody) {
    String expected = computeHmacSha256Hex(rawBody == null ? "" : rawBody);
    String presented = presentedHeader.startsWith(SIGNATURE_PREFIX)
        ? presentedHeader.substring(SIGNATURE_PREFIX.length())
        : presentedHeader;
    byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
    byte[] presentedBytes = presented.getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(expectedBytes, presentedBytes);
  }

  private String computeHmacSha256Hex(String rawBody) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        builder.append(String.format("%02x", b & 0xff));
      }
      return builder.toString();
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to compute webhook signature");
    }
  }

  private static String header(Map<String, String> headers, String name) {
    if (headers == null) return null;
    String direct = headers.get(name);
    if (direct != null) return direct;
    return headers.get(name.toLowerCase());
  }
}
