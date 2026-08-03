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
 * <p>Verification authority is <b>server-owned</b>. Whether unsigned fixture intake is allowed is
 * decided by {@link WebhookVerificationAuthority}, never by a client-supplied header. When the Meta
 * app secret is configured this performs deterministic HMAC-SHA256 verification of
 * {@code X-Hub-Signature-256} against the raw body and <b>fails closed</b> on a missing or mismatched
 * signature. When the secret is absent, requests fail closed unless server-owned fixture mode is
 * explicitly enabled for a non-production profile.
 */
@Service
public class WhatsAppSignatureVerifier implements WebhookSignatureVerifier {
  private static final String SIGNATURE_HEADER = "X-Hub-Signature-256";
  private static final String SIGNATURE_PREFIX = "sha256=";

  private final String appSecret;
  private final WebhookVerificationAuthority authority;

  /** Direct unit-test construction without Spring. */
  public WhatsAppSignatureVerifier() {
    this("", WebhookVerificationAuthority.forTests(false, false));
  }

  /** Direct unit-test construction with an explicit app secret. */
  public WhatsAppSignatureVerifier(String appSecret) {
    this(appSecret, WebhookVerificationAuthority.forTests(false, false));
  }

  /** Direct unit-test construction with explicit authority. */
  public WhatsAppSignatureVerifier(String appSecret, WebhookVerificationAuthority authority) {
    this.appSecret = appSecret == null ? "" : appSecret;
    this.authority = authority == null ? WebhookVerificationAuthority.forTests(false, false) : authority;
  }

  @Autowired
  public WhatsAppSignatureVerifier(
      @Value("${orderpilot.channel-gateway.whatsapp.app-secret:}") String appSecret,
      WebhookVerificationAuthority authority,
      // Disambiguate from the (String, WebhookVerificationAuthority) test constructor for Spring.
      org.springframework.core.env.Environment environment) {
    this(appSecret, authority);
  }

  @Override
  public WebhookSignatureVerificationResult verify(
      Map<String, String> requestHeaders, String rawBody, ChannelType channelType, UUID tenantId) {
    if (authority.allowsUnsignedFixtureIntake() && appSecret.isBlank()) {
      return new WebhookSignatureVerificationResult(
          true,
          WebhookVerificationMode.DISABLED_FIXTURE_MODE,
          providerName(),
          "server-owned fixture mode accepted without production Meta app secret");
    }
    if (appSecret.isBlank()) {
      return new WebhookSignatureVerificationResult(
          false, WebhookVerificationMode.FAILED, providerName(), "webhook signature verification failed");
    }
    String presented = header(requestHeaders, SIGNATURE_HEADER);
    if (presented == null || presented.isBlank()) {
      return new WebhookSignatureVerificationResult(
          false, WebhookVerificationMode.FAILED, providerName(), "webhook signature verification failed");
    }
    if (!signatureMatches(presented, rawBody)) {
      return new WebhookSignatureVerificationResult(
          false, WebhookVerificationMode.FAILED, providerName(), "webhook signature verification failed");
    }
    return new WebhookSignatureVerificationResult(
        true, WebhookVerificationMode.CONFIGURED_VERIFY_ONLY, providerName(), "webhook signature verified");
  }

  public boolean isVerified(String signatureHeader, String rawBody) {
    return !appSecret.isBlank() && signatureHeader != null && signatureMatches(signatureHeader, rawBody);
  }

  public String mode() {
    return verificationMode().name();
  }

  @Override
  public WebhookVerificationMode verificationMode() {
    if (!appSecret.isBlank()) {
      return WebhookVerificationMode.CONFIGURED_VERIFY_ONLY;
    }
    return authority.allowsUnsignedFixtureIntake()
        ? WebhookVerificationMode.DISABLED_FIXTURE_MODE
        : WebhookVerificationMode.FAILED;
  }

  @Override
  public String providerName() {
    return "WHATSAPP";
  }

  private boolean signatureMatches(String presentedHeader, String rawBody) {
    String expected = computeHmacSha256Hex(rawBody == null ? "" : rawBody);
    String presented =
        presentedHeader.startsWith(SIGNATURE_PREFIX)
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
