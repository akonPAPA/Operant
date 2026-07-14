package com.orderpilot.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Byte-identical gateway signature v2 canonical contract shared with the TypeScript BFF signer.
 *
 * <pre>
 * ORDERPILOT_GATEWAY_V2
 * METHOD
 * PATH
 * RAW_QUERY
 * CONTENT_TYPE
 * BODY_SHA256_HEX
 * TENANT_ID
 * ACTOR_ID
 * PERMISSIONS
 * TIMESTAMP
 * NONCE
 * </pre>
 *
 * <p>Fields are joined with a single LF ({@code \n}). CR/LF inside any field is rejected.
 */
public final class GatewayV2Canonical {
  public static final String PROTOCOL_MARKER = "ORDERPILOT_GATEWAY_V2";
  public static final String SIGNATURE_VERSION = "2";
  public static final String VERSION_HEADER = "X-OrderPilot-Signature-Version";
  public static final String CONTENT_SHA256_HEADER = "X-OrderPilot-Content-SHA256";
  public static final String EMPTY_BODY_SHA256_HEX =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  private GatewayV2Canonical() {}

  public static String build(
      String method,
      String path,
      String rawQuery,
      String contentType,
      String bodySha256Hex,
      String tenantId,
      String actorId,
      String permissions,
      long timestampEpoch,
      String nonce) {
    requireNoControlSeparators(method, "method");
    requireNoControlSeparators(path, "path");
    requireNoControlSeparators(rawQuery, "query");
    requireNoControlSeparators(contentType, "contentType");
    requireNoControlSeparators(bodySha256Hex, "bodySha256");
    requireNoControlSeparators(tenantId, "tenantId");
    requireNoControlSeparators(actorId, "actorId");
    requireNoControlSeparators(permissions, "permissions");
    requireNoControlSeparators(nonce, "nonce");
    return PROTOCOL_MARKER
        + "\n"
        + method.toUpperCase(Locale.ROOT)
        + "\n"
        + path
        + "\n"
        + rawQuery
        + "\n"
        + contentType
        + "\n"
        + bodySha256Hex.toLowerCase(Locale.ROOT)
        + "\n"
        + tenantId.trim()
        + "\n"
        + actorId.trim()
        + "\n"
        + permissions.trim()
        + "\n"
        + timestampEpoch
        + "\n"
        + nonce.trim();
  }

  public static String sha256Hex(byte[] bodyBytes) {
    byte[] input = bodyBytes == null ? new byte[0] : bodyBytes;
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return toHex(digest.digest(input));
    } catch (Exception ex) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  public static boolean isValidContentSha256Hex(String value) {
    if (value == null) {
      return false;
    }
    if (!value.equals(value.trim())) {
      return false;
    }
    return value.matches("^[0-9a-fA-F]{64}$");
  }

  public static String normalizeContentType(String contentType) {
    if (contentType == null || contentType.isBlank()) {
      return "";
    }
    String primary = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    if ("application/json".equals(primary)) {
      return "application/json";
    }
    return primary;
  }

  private static void requireNoControlSeparators(String value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\n' || c == '\r') {
        throw new IllegalArgumentException(field + " must not contain CR/LF");
      }
    }
  }

  private static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }

  /** UTF-8 helper for empty-body constant verification in tests. */
  public static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
