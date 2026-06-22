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
 * OP-CAP-42I — Meta Messenger (Path-2, connection-based) provider webhook verifier exemplar.
 *
 * <p>Meta Messenger signs its webhook deliveries exactly like WhatsApp/Meta: an HMAC-SHA256 of the
 * delivered body keyed by the Meta <b>App Secret</b>, presented as the {@code X-Hub-Signature-256}
 * header with a {@code sha256=} prefix. Verification authority is <b>server-owned</b>: whether a
 * signature is required is decided by the presence of a server-configured app secret
 * ({@code orderpilot.channel-gateway.meta-messenger.app-secret}), never by a client-supplied header and
 * never by the connection-stored secret <i>reference</i> (the raw secret is intentionally not exposed on
 * the connection model — see {@link AbstractProviderWebhookVerifier}). This mirrors the already-proven
 * server-configured Path-1 pattern in {@link WhatsAppSignatureVerifier}; it performs no network call and
 * uses no provider SDK.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>app secret configured <b>and</b> connection in an enforcing signature mode
 *       ({@code SIGNATURE_HEADER}/{@code PROVIDER_SPECIFIC}) → real HMAC-SHA256 verification: a missing or
 *       mismatched/tampered/wrong-secret signature <b>fails closed</b> ({@code REJECTED}); a valid
 *       signature is accepted as {@code CONFIGURED_VERIFY_ONLY} (an explicit verified status, not a
 *       local-dev skip);</li>
 *   <li>app secret not configured, non-signature mode, unsupported mode, or local-dev → defers to the
 *       shared fail-closed contract of {@link AbstractProviderWebhookVerifier}; the only honest accept
 *       there is the explicit {@code DISABLED_FOR_LOCAL_DEV} skip. It never falsely reports a verified
 *       status for an unconfigured deployment.</li>
 * </ul>
 *
 * <p>Meta's {@code X-Hub-Signature-256} carries no timestamp or nonce, so there is no signature-layer
 * freshness/replay window to enforce here (not faked — see OP-CAP-42I docs); replay continuity is the
 * existing service-level dedup in {@link ChannelEventNormalizationService}. As of OP-CAP-42J the signed
 * input is the <b>byte-exact</b> raw request body: the controller receives the Meta webhook body as a raw
 * String and {@link ChannelEventNormalizationService} verifies this {@code rawPayload} against the
 * presented signature before parsing/normalizing the JSON. This matches real Meta production behaviour —
 * a semantically equivalent but byte-different body (whitespace/key-order) carrying a signature for the
 * original bytes fails closed. This verifier is byte-faithful: it HMACs the literal {@code rawPayload}
 * string it is given and performs a constant-time compare, never echoing the signature, secret, or body.
 */
@Component
public class MetaMessengerWebhookVerifier extends AbstractProviderWebhookVerifier {
  private static final String SIGNATURE_HEADER = "x-hub-signature-256";
  private static final String SIGNATURE_PREFIX = "sha256=";

  /** Server-configured Meta app secret. Blank means real verification is not configured (fail-closed). */
  private final String appSecret;

  public MetaMessengerWebhookVerifier() {
    this("");
  }

  // Explicitly mark the property constructor as the Spring-autowired one (same lesson as OP-CAP-42G's
  // WhatsAppSignatureVerifier fix): with two constructors and no @Autowired marker, Spring would silently
  // select the no-arg constructor and the server-configured secret would never be applied at runtime. The
  // no-arg constructor is retained for direct unit construction in tests.
  @Autowired
  public MetaMessengerWebhookVerifier(@Value("${orderpilot.channel-gateway.meta-messenger.app-secret:}") String appSecret) {
    this.appSecret = appSecret == null ? "" : appSecret;
  }

  @Override
  public ChannelProviderType providerType() {
    return ChannelProviderType.META_MESSENGER;
  }

  @Override
  public VerificationResult verify(ChannelConnection connection, Map<String, String> headers, String rawPayload) {
    String mode = connection.getWebhookVerificationMode();
    boolean enforcingSignatureMode = "SIGNATURE_HEADER".equals(mode) || "PROVIDER_SPECIFIC".equals(mode);
    // Real server-configured Meta HMAC verification applies only in an enforcing signature mode with a
    // configured app secret. Every other case (local-dev skip, unconfigured secret, shared-secret mode,
    // unsupported mode) defers to the shared fail-closed contract so behaviour is never weakened.
    if (enforcingSignatureMode && !appSecret.isBlank()) {
      String presented = header(headers, SIGNATURE_HEADER);
      if (presented == null || presented.isBlank()) {
        return VerificationResult.rejected("Missing provider signature header");
      }
      if (!signatureMatches(presented, rawPayload)) {
        return VerificationResult.rejected("Meta webhook signature verification failed");
      }
      return VerificationResult.configuredVerifyOnly("Meta webhook signature verified");
    }
    return super.verify(connection, headers, rawPayload);
  }

  private boolean signatureMatches(String presentedHeader, String rawPayload) {
    String expected = computeHmacSha256Hex(rawPayload == null ? "" : rawPayload);
    String presented = presentedHeader.startsWith(SIGNATURE_PREFIX)
        ? presentedHeader.substring(SIGNATURE_PREFIX.length())
        : presentedHeader;
    byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
    byte[] presentedBytes = presented.getBytes(StandardCharsets.UTF_8);
    // Constant-time comparison; never echoes either value.
    return MessageDigest.isEqual(expectedBytes, presentedBytes);
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

  private static String header(Map<String, String> headers, String name) {
    if (headers == null) return null;
    // HTTP header names are case-insensitive; the @RequestHeader map preserves the original case
    // ("X-Hub-Signature-256"), so match case-insensitively rather than assuming a lowercased key.
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
        return entry.getValue();
      }
    }
    return null;
  }
}
