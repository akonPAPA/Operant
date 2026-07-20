package com.operant.ctl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Control-plane request signer. This is intentionally separate from the tenant/browser gateway
 * signature contract: the CLI signs a bounded control credential reference and freshness/audience
 * facts only. Core resolves the control principal and route permission after verification.
 */
final class ControlPlaneSigner {
  static final String PROTOCOL_MARKER = "OPERANT_CONTROL_V1";
  static final String SIGNATURE_VERSION = "1";
  static final String AUDIENCE = "orderpilot-control-plane";
  static final String EMPTY_BODY_SHA256_HEX =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  static final String CREDENTIAL_HEADER = "X-OrderPilot-Control-Credential";
  static final String AUDIENCE_HEADER = "X-OrderPilot-Control-Audience";
  static final String TIMESTAMP_HEADER = "X-OrderPilot-Control-Timestamp";
  static final String NONCE_HEADER = "X-OrderPilot-Control-Nonce";
  static final String VERSION_HEADER = "X-OrderPilot-Control-Signature-Version";
  static final String CONTENT_SHA256_HEADER = "X-OrderPilot-Control-Content-SHA256";
  static final String SIGNATURE_HEADER = "X-OrderPilot-Control-Signature";

  private static final SecureRandom RANDOM = new SecureRandom();

  private final byte[] secretKey;
  private final String credentialAlias;

  ControlPlaneSigner(byte[] secretKey, String credentialAlias) {
    this.secretKey = secretKey.clone();
    this.credentialAlias = credentialAlias;
  }

  /** Signed headers for a bodyless GET to a fixed control API path (no query string). */
  Map<String, String> signedGetHeaders(String path, long timestampEpoch) {
    return signedGetHeaders(path, "", timestampEpoch);
  }

  /**
   * Signed headers for a bodyless GET to a fixed control API path with a bounded raw query string.
   * The {@code rawQuery} MUST be exactly the query string placed on the request line (no leading
   * {@code ?}), because Core verifies the signature over {@code request.getQueryString()} verbatim -
   * any post-signing tampering with the query breaks the HMAC and is rejected.
   */
  Map<String, String> signedGetHeaders(String path, String rawQuery, long timestampEpoch) {
    String nonce = newNonce();
    String canonical = canonical(
        "GET", path, rawQuery == null ? "" : rawQuery, "", EMPTY_BODY_SHA256_HEX, AUDIENCE,
        credentialAlias, timestampEpoch, nonce);
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put(CREDENTIAL_HEADER, credentialAlias);
    headers.put(AUDIENCE_HEADER, AUDIENCE);
    headers.put(TIMESTAMP_HEADER, Long.toString(timestampEpoch));
    headers.put(NONCE_HEADER, nonce);
    headers.put(VERSION_HEADER, SIGNATURE_VERSION);
    headers.put(CONTENT_SHA256_HEADER, EMPTY_BODY_SHA256_HEX);
    headers.put(SIGNATURE_HEADER, hmacHex(canonical));
    return headers;
  }

  static String canonical(
      String method,
      String path,
      String rawQuery,
      String contentType,
      String bodySha256Hex,
      String audience,
      String credentialAlias,
      long timestampEpoch,
      String nonce) {
    return PROTOCOL_MARKER
        + "\n" + method.toUpperCase(Locale.ROOT)
        + "\n" + path
        + "\n" + rawQuery
        + "\n" + contentType
        + "\n" + bodySha256Hex.toLowerCase(Locale.ROOT)
        + "\n" + audience.trim()
        + "\n" + credentialAlias.trim()
        + "\n" + timestampEpoch
        + "\n" + nonce.trim();
  }

  static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  String hmacHex(String canonical) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException failure) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", failure);
    }
  }

  private static String newNonce() {
    byte[] nonce = new byte[16];
    RANDOM.nextBytes(nonce);
    return HexFormat.of().formatHex(nonce);
  }
}