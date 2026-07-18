package com.orderpilot.security;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

final class ControlPlaneCredentialRegistry {
  static final String PRINCIPAL_TYPE = "CONTROL";
  static final String STATUS_ENABLED = "ENABLED";
  static final String STATUS_DISABLED = "DISABLED";
  static final String STATUS_REVOKED = "REVOKED";
  private static final Pattern ALIAS_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");

  private final Optional<ControlPlaneCredentialRecord> record;
  private final Clock clock;

  ControlPlaneCredentialRegistry(
      String alias,
      String sharedSecret,
      String audience,
      String status,
      String validFrom,
      String expiresAt,
      boolean revoked,
      String permissions,
      String keyVersion,
      Clock clock) {
    this.record = buildRecord(alias, sharedSecret, audience, status, validFrom, expiresAt, revoked, permissions,
        keyVersion);
    this.clock = clock;
  }

  Optional<ControlPlaneCredentialRecord> findActive(String alias, String audience) {
    Instant now = clock.instant();
    return record
        .filter(candidate -> candidate.alias().equals(alias))
        .filter(candidate -> candidate.audience().equals(audience))
        .filter(candidate -> STATUS_ENABLED.equals(candidate.status()))
        .filter(candidate -> !candidate.revoked())
        .filter(candidate -> !now.isBefore(candidate.validFrom()))
        .filter(candidate -> now.isBefore(candidate.expiresAt()));
  }

  private static Optional<ControlPlaneCredentialRecord> buildRecord(
      String alias,
      String sharedSecret,
      String audience,
      String status,
      String validFrom,
      String expiresAt,
      boolean revoked,
      String permissions,
      String keyVersion) {
    if (alias == null || !ALIAS_PATTERN.matcher(alias.trim()).matches()) {
      return Optional.empty();
    }
    byte[] key = GatewayHmacKeyCodec.tryDecode(sharedSecret);
    if (key == null) {
      return Optional.empty();
    }
    String resolvedAudience = audience == null || audience.isBlank()
        ? ControlPlaneProtocol.AUDIENCE
        : audience.trim();
    String resolvedStatus = status == null || status.isBlank()
        ? STATUS_DISABLED
        : status.trim().toUpperCase(Locale.ROOT);
    Instant resolvedValidFrom = parseInstant(validFrom, Instant.EPOCH);
    Instant resolvedExpiresAt = parseInstant(expiresAt, Instant.parse("9999-12-31T23:59:59Z"));
    if (resolvedValidFrom == null || resolvedExpiresAt == null) {
      return Optional.empty();
    }
    Set<ApiPermission> resolvedPermissions = parsePermissions(permissions);
    if (resolvedPermissions.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new ControlPlaneCredentialRecord(
        alias.trim(),
        PRINCIPAL_TYPE,
        key,
        resolvedAudience,
        resolvedStatus,
        resolvedValidFrom,
        resolvedExpiresAt,
        revoked,
        resolvedPermissions,
        keyVersion == null || keyVersion.isBlank() ? "1" : keyVersion.trim()));
  }

  private static Instant parseInstant(String value, Instant fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Instant.parse(value.trim());
    } catch (DateTimeParseException invalid) {
      return null;
    }
  }

  private static Set<ApiPermission> parsePermissions(String raw) {
    List<String> values = raw == null || raw.isBlank()
        ? List.of(ApiPermission.STAFF_CONTROL_READ.name(), ApiPermission.STAFF_CONTROL_DIAGNOSE.name())
        : Arrays.stream(raw.split(",")).map(String::trim).filter(value -> !value.isBlank()).toList();
    java.util.EnumSet<ApiPermission> permissions = java.util.EnumSet.noneOf(ApiPermission.class);
    for (String value : values) {
      try {
        ApiPermission permission = ApiPermission.valueOf(value);
        if (permission.name().startsWith("STAFF_CONTROL_")) {
          permissions.add(permission);
        }
      } catch (IllegalArgumentException ignored) {
        return Set.of();
      }
    }
    return Set.copyOf(permissions);
  }

  record ControlPlaneCredentialRecord(
      String alias,
      String principalType,
      byte[] keyMaterial,
      String audience,
      String status,
      Instant validFrom,
      Instant expiresAt,
      boolean revoked,
      Set<ApiPermission> permissions,
      String keyVersion) {
    ControlPlaneCredentialRecord {
      keyMaterial = keyMaterial.clone();
      permissions = Set.copyOf(permissions);
    }

    byte[] keyMaterialCopy() {
      return keyMaterial.clone();
    }

    String keyMaterialFingerprintForTests() {
      return HexFormat.of().formatHex(keyMaterial);
    }
  }
}
