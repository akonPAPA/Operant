package com.orderpilot.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Bounded in-memory registry for the independently configured control-plane credentials.
 *
 * <p>The registry supports at most one Operant staff credential and one lifecycle-executor credential.
 * Records are indexed by exact credential alias. Alias, logical principal id, and key material must be
 * distinct across the two slots. This prevents permission-family mixing and prevents one credential from
 * impersonating both a human support principal and a machine executor.
 */
final class ControlPlaneCredentialRegistry {
  static final String STATUS_ENABLED = "ENABLED";
  static final String STATUS_DISABLED = "DISABLED";
  static final String STATUS_REVOKED = "REVOKED";

  private final Map<String, ControlPlaneCredentialRecord> recordsByAlias;
  private final Clock clock;

  /**
   * Compatibility constructor for existing focused tests and the original single-credential slot. The
   * logical principal id defaults to the server-configured alias and the principal type is inferred from
   * the single allowed permission family.
   */
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
    this(
        ControlPlaneCredentialConfigurationValidator.validatedRecord(
            alias,
            sharedSecret,
            audience,
            status,
            validFrom,
            expiresAt,
            revoked,
            permissions,
            keyVersion,
            null,
            clock,
            false),
        Optional.empty(),
        clock);
  }

  ControlPlaneCredentialRegistry(
      Optional<ControlPlaneCredentialRecord> staffRecord,
      Optional<ControlPlaneCredentialRecord> executorRecord,
      Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
    LinkedHashMap<String, ControlPlaneCredentialRecord> records = new LinkedHashMap<>(2);
    staffRecord.ifPresent(record -> addDistinct(records, record));
    executorRecord.ifPresent(record -> addDistinct(records, record));
    this.recordsByAlias = Map.copyOf(records);
  }

  Optional<ControlPlaneCredentialRecord> findActive(String alias, String audience) {
    Instant now = clock.instant();
    return Optional.ofNullable(recordsByAlias.get(alias))
        .filter(candidate -> candidate.audience().equals(audience))
        .filter(candidate -> STATUS_ENABLED.equals(candidate.status()))
        .filter(candidate -> !candidate.revoked())
        .filter(candidate -> !now.isBefore(candidate.validFrom()))
        .filter(candidate -> now.isBefore(candidate.expiresAt()));
  }

  private static void addDistinct(
      Map<String, ControlPlaneCredentialRecord> records,
      ControlPlaneCredentialRecord candidate) {
    if (records.containsKey(candidate.alias())) {
      throw new IllegalArgumentException("control-plane credential aliases must be unique");
    }
    for (ControlPlaneCredentialRecord existing : records.values()) {
      if (existing.principalId().equals(candidate.principalId())) {
        throw new IllegalArgumentException("control-plane logical principal ids must be unique across planes");
      }
      if (sameKeyMaterial(existing, candidate)) {
        throw new IllegalArgumentException("control-plane credentials must not reuse key material");
      }
    }
    records.put(candidate.alias(), candidate);
  }

  private static boolean sameKeyMaterial(
      ControlPlaneCredentialRecord first,
      ControlPlaneCredentialRecord second) {
    byte[] firstCopy = first.keyMaterialCopy();
    byte[] secondCopy = second.keyMaterialCopy();
    try {
      return MessageDigest.isEqual(firstCopy, secondCopy);
    } finally {
      Arrays.fill(firstCopy, (byte) 0);
      Arrays.fill(secondCopy, (byte) 0);
    }
  }

  /**
   * Immutable control-plane credential. Key material is registry-owned: it is cloned on construction and
   * never exposed directly. Callers take a defensive copy for HMAC verification and must zero it after
   * use. {@code toString()} deliberately excludes the secret and logical principal id.
   */
  static final class ControlPlaneCredentialRecord {
    private final String principalId;
    private final ControlPlanePrincipalType principalType;
    private final String alias;
    private final byte[] keyMaterial;
    private final String audience;
    private final String status;
    private final Instant validFrom;
    private final Instant expiresAt;
    private final boolean revoked;
    private final Set<ApiPermission> permissions;
    private final String keyVersion;

    ControlPlaneCredentialRecord(
        String principalId,
        ControlPlanePrincipalType principalType,
        String alias,
        byte[] keyMaterial,
        String audience,
        String status,
        Instant validFrom,
        Instant expiresAt,
        boolean revoked,
        Set<ApiPermission> permissions,
        String keyVersion) {
      this.principalId = principalId;
      this.principalType = principalType;
      this.alias = alias;
      this.keyMaterial = keyMaterial.clone();
      this.audience = audience;
      this.status = status;
      this.validFrom = validFrom;
      this.expiresAt = expiresAt;
      this.revoked = revoked;
      this.permissions = Set.copyOf(permissions);
      this.keyVersion = keyVersion;
    }

    String principalId() {
      return principalId;
    }

    ControlPlanePrincipalType principalType() {
      return principalType;
    }

    String alias() {
      return alias;
    }

    String audience() {
      return audience;
    }

    String status() {
      return status;
    }

    Instant validFrom() {
      return validFrom;
    }

    Instant expiresAt() {
      return expiresAt;
    }

    boolean revoked() {
      return revoked;
    }

    Set<ApiPermission> permissions() {
      return permissions;
    }

    String keyVersion() {
      return keyVersion;
    }

    byte[] keyMaterialCopy() {
      return keyMaterial.clone();
    }

    ControlPlanePrincipal principal() {
      return new ControlPlanePrincipal(principalId, alias, keyVersion, principalType);
    }

    /** Non-reversible SHA-256 fingerprint of key material for focused security assertions only. */
    String keyMaterialFingerprintForTests() {
      try {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(keyMaterial));
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("SHA-256 unavailable", e);
      }
    }

    @Override
    public String toString() {
      return "ControlPlaneCredentialRecord{alias="
          + alias
          + ", principalType="
          + principalType
          + ", audience="
          + audience
          + ", status="
          + status
          + ", keyVersion="
          + keyVersion
          + ", revoked="
          + revoked
          + "}";
    }
  }
}
