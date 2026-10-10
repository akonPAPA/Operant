package com.orderpilot.aibot.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/** Canonical SHA-256 fingerprint for AI job request envelopes. Dependency-free. */
public final class AiRequestFingerprint {
  private AiRequestFingerprint() {}

  public static String sha256Hex(String canonicalUtf8) {
    Objects.requireNonNull(canonicalUtf8, "canonicalUtf8");
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(canonicalUtf8.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("sha256_unavailable", ex);
    }
  }

  public static String forGeneration(
      String tenantId,
      String botPublicId,
      int botVersion,
      String normalizedDesiredBehavior,
      List<String> approvedIntents,
      String promptVersion,
      String schemaVersion,
      String providerPolicyVersion) {
    return sha256Hex(
        canonical(
            "BOT_DEFINITION_GENERATION",
            tenantId,
            botPublicId,
            botVersion,
            normalizedDesiredBehavior,
            "und",
            approvedIntents,
            List.of(),
            promptVersion,
            schemaVersion,
            providerPolicyVersion));
  }

  public static String forPreview(
      String tenantId,
      String botPublicId,
      int botVersion,
      String normalizedInput,
      String locale,
      List<String> approvedIntents,
      List<String> approvedActionBindings,
      String promptVersion,
      String schemaVersion,
      String providerPolicyVersion) {
    return sha256Hex(
        canonical(
            "BOT_INTENT_CLASSIFICATION",
            tenantId,
            botPublicId,
            botVersion,
            normalizedInput,
            locale,
            approvedIntents,
            approvedActionBindings,
            promptVersion,
            schemaVersion,
            providerPolicyVersion));
  }

  private static String canonical(
      String purpose,
      String tenantId,
      String botPublicId,
      int botVersion,
      String normalizedInput,
      String locale,
      List<String> approvedIntents,
      List<String> approvedActionBindings,
      String promptVersion,
      String schemaVersion,
      String providerPolicyVersion) {
    return String.join(
        "\n",
        "v1",
        nullToEmpty(tenantId),
        purpose,
        nullToEmpty(botPublicId),
        Integer.toString(botVersion),
        nullToEmpty(normalizedInput),
        nullToEmpty(locale).toLowerCase(Locale.ROOT),
        joinSorted(approvedIntents),
        joinSorted(approvedActionBindings),
        nullToEmpty(promptVersion),
        nullToEmpty(schemaVersion),
        nullToEmpty(providerPolicyVersion));
  }

  private static String joinSorted(List<String> values) {
    if (values == null || values.isEmpty()) {
      return "";
    }
    return values.stream()
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(s -> s.toUpperCase(Locale.ROOT))
        .sorted()
        .collect(Collectors.joining(","));
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value.trim();
  }
}
