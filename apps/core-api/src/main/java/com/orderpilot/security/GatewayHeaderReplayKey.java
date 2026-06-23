package com.orderpilot.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class GatewayHeaderReplayKey {
  private GatewayHeaderReplayKey() {}

  static String digestKey(String keyPrefix, String tenantId, String actorId, String nonce) {
    String material = normalized(tenantId) + "\n" + normalized(actorId) + "\n" + normalized(nonce);
    return normalizedPrefix(keyPrefix) + ":" + sha256Hex(material);
  }

  private static String normalized(String value) {
    return value == null ? "" : value.trim();
  }

  private static String normalizedPrefix(String keyPrefix) {
    String value = normalized(keyPrefix);
    return value.isBlank() ? "op:gw-replay" : value;
  }

  private static String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 digest unavailable", ex);
    }
  }
}
