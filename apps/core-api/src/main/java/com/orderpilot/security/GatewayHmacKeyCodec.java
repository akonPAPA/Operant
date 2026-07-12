package com.orderpilot.security;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Cryptographic contract for the BFF ↔ Core gateway HMAC shared secret.
 *
 * <p>Configured value must be exactly 64 hexadecimal characters (32 decoded bytes / 256 bits).
 * HMAC uses the decoded raw bytes via {@link javax.crypto.spec.SecretKeySpec}, never the ASCII hex
 * text. Invalid values fail closed; the secret value is never placed in exceptions or logs.
 */
public final class GatewayHmacKeyCodec {
  private static final Pattern HEX_64 = Pattern.compile("^[0-9a-fA-F]{64}$");

  private GatewayHmacKeyCodec() {}

  /** Decode a configured secret; returns null when blank/missing (caller decides fail-closed). */
  public static byte[] tryDecode(String configured) {
    if (configured == null) {
      return null;
    }
    // Reject leading/trailing whitespace by requiring exact match without trim success path that
    // would silently accept padded values: trim only to detect padding as invalid.
    if (!configured.equals(configured.trim())) {
      return null;
    }
    if (configured.isEmpty()) {
      return null;
    }
    if (!HEX_64.matcher(configured).matches()) {
      return null;
    }
    if (isWeakOrPlaceholderHex(configured)) {
      return null;
    }
    return decodeHexStrict(configured);
  }

  /**
   * Production/config validation: blank or invalid → IllegalStateException without echoing the
   * secret value.
   */
  public static byte[] requireValid(String propertyName, String configured) {
    if (configured == null || configured.isBlank()) {
      throw new IllegalStateException(
          propertyName + " must be configured as exactly 64 hexadecimal characters "
              + "(32 random bytes; generate with: openssl rand -hex 32)");
    }
    if (!configured.equals(configured.trim())) {
      throw new IllegalStateException(
          propertyName + " must not contain leading or trailing whitespace");
    }
    if (!HEX_64.matcher(configured).matches()) {
      throw new IllegalStateException(
          propertyName + " must be exactly 64 hexadecimal characters "
              + "(32 random bytes; generate with: openssl rand -hex 32)");
    }
    if (isWeakOrPlaceholderHex(configured)) {
      throw new IllegalStateException(
          propertyName + " must not use a placeholder or low-entropy repeated hex pattern");
    }
    byte[] decoded = decodeHexStrict(configured);
    if (decoded == null) {
      throw new IllegalStateException(propertyName + " could not be decoded");
    }
    return decoded;
  }

  public static boolean isValidConfiguredValue(String configured) {
    return tryDecode(configured) != null;
  }

  static boolean isWeakOrPlaceholderHex(String hex) {
    String lower = hex.toLowerCase(Locale.ROOT);
    if (lower.chars().distinct().count() <= 2) {
      return true;
    }
    // Reject obvious repeating 1–4 nibble blocks covering the whole secret.
    for (int block = 1; block <= 4; block++) {
      if (lower.length() % block != 0) {
        continue;
      }
      String unit = lower.substring(0, block);
      if (lower.equals(unit.repeat(lower.length() / block))) {
        return true;
      }
    }
    return lower.contains("deadbeef")
        || lower.contains("cafebabe")
        || lower.contains("change")
        || lower.contains("secret")
        || lower.contains("password")
        || lower.contains("placeholder");
  }

  private static byte[] decodeHexStrict(String hex) {
    int len = hex.length();
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
}
