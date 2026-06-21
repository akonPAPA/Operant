package com.orderpilot.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * OP-CAP-16K — narrow HMAC verifier for the runtime entitlement admin control plane.
 *
 * <p>This is <b>not</b> a full authentication subsystem. It hardens the existing trusted-header actor
 * model: when a signing secret is configured, an actor header must be accompanied by a fresh HMAC
 * signature over the canonical string {@code tenantId + "\n" + actorId + "\n" + timestamp}. The
 * gateway/edge that injects {@code X-OrderPilot-Actor-Id} also signs it, so a request body can never
 * assert the audit actor and a forged actor header cannot pass without the secret.
 *
 * <p>Security properties: HMAC-SHA-256, constant-time comparison ({@link MessageDigest#isEqual}), a
 * bounded timestamp freshness window, and no secret/expected-signature ever placed in exceptions or
 * logs. When no secret is configured the verifier is inert ({@link #isConfigured()} is false) and the
 * caller keeps the 16J trusted-header fallback for local/dev/test.
 */
public class SignedActorVerifier {
  private static final String HMAC_ALGORITHM = "HmacSHA256";

  private final byte[] secret;
  private final long maxSkewSeconds;
  private final Clock clock;

  public SignedActorVerifier(String signingSecret, long maxSkewSeconds, Clock clock) {
    this.secret = signingSecret == null || signingSecret.isBlank()
        ? null
        : signingSecret.getBytes(StandardCharsets.UTF_8);
    this.maxSkewSeconds = maxSkewSeconds;
    this.clock = clock;
  }

  /** Whether a signing secret is configured. When false, callers use the trusted-header fallback. */
  public boolean isConfigured() {
    return secret != null;
  }

  /**
   * Verify the signature over {@code (tenantId, actorId, timestamp)}. Throws {@link
   * ActorVerificationException} (→ 401) on any missing/invalid/stale/malformed input. No-op when no
   * secret is configured.
   */
  public void verify(UUID tenantId, UUID actorId, String signatureHeader, String timestampHeader) {
    if (!isConfigured()) {
      return;
    }
    if (signatureHeader == null || signatureHeader.isBlank()) {
      throw new ActorVerificationException("Actor signature is required");
    }
    if (timestampHeader == null || timestampHeader.isBlank()) {
      throw new ActorVerificationException("Actor signature timestamp is required");
    }
    long timestampEpoch;
    try {
      timestampEpoch = Long.parseLong(timestampHeader.trim());
    } catch (NumberFormatException ex) {
      throw new ActorVerificationException("Actor signature timestamp is malformed");
    }
    long nowEpoch = clock.instant().getEpochSecond();
    if (Math.abs(nowEpoch - timestampEpoch) > maxSkewSeconds) {
      throw new ActorVerificationException("Actor signature timestamp is outside the allowed window");
    }
    String canonical = tenantId + "\n" + actorId + "\n" + timestampEpoch;
    if (!matchesHmacHex(secret, canonical, signatureHeader)) {
      throw new ActorVerificationException("Actor signature is invalid");
    }
  }

  /** Lowercase hex HMAC-SHA-256 of {@code message} under {@code secret} — exposed for callers/tests. */
  public static String hmacHex(String signingSecret, String message) {
    return toHex(hmac(signingSecret.getBytes(StandardCharsets.UTF_8), message));
  }

  /** Constant-time verification for callers that share the HMAC-SHA-256 gateway boundary. */
  public static boolean matchesHmacHex(String signingSecret, String message, String signatureHeader) {
    if (signingSecret == null || signingSecret.isBlank()) {
      return false;
    }
    return matchesHmacHex(signingSecret.getBytes(StandardCharsets.UTF_8), message, signatureHeader);
  }

  private static boolean matchesHmacHex(byte[] secret, String message, String signatureHeader) {
    if (signatureHeader == null || signatureHeader.isBlank()) {
      return false;
    }
    byte[] expected = hmac(secret, message);
    byte[] presented = decodeHexLenient(signatureHeader.trim());
    return presented != null && MessageDigest.isEqual(expected, presented);
  }

  private static byte[] hmac(byte[] secret, String message) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
      return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
    } catch (Exception ex) {
      // Configuration/JCA error — never leak details.
      throw new ActorVerificationException("Actor signature could not be verified");
    }
  }

  private static byte[] decodeHexLenient(String hex) {
    int len = hex.length();
    if (len == 0 || (len & 1) == 1) {
      return null;
    }
    byte[] out = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
      int hi = Character.digit(hex.charAt(i), 16);
      int lo = Character.digit(hex.charAt(i + 1), 16);
      if (hi < 0 || lo < 0) {
        return null;
      }
      out[i / 2] = (byte) ((hi << 4) | lo);
    }
    return out;
  }

  private static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }
}
