package com.orderpilot.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class ControlPlaneCredentialRegistry {
  static final String PRINCIPAL_TYPE = "CONTROL";
  static final String STATUS_ENABLED = "ENABLED";
  static final String STATUS_DISABLED = "DISABLED";
  static final String STATUS_REVOKED = "REVOKED";

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
    this.clock = clock;
    this.record = ControlPlaneCredentialConfigurationValidator.validatedRecord(
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
        false);
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

  /**
   * Immutable control-plane credential. The key material is registry-owned: it is cloned on
   * construction and never handed out through an accessor. Callers that need the secret for HMAC
   * verification must take a defensive copy via {@link #keyMaterialCopy()} and zero it after use.
   * Equality, hashing, and {@code toString()} deliberately exclude the secret so it cannot leak
   * through logs, exceptions, or debugger surfaces.
   */
  static final class ControlPlaneCredentialRecord {
    private final String alias;
    private final String principalType;
    private final byte[] keyMaterial;
    private final String audience;
    private final String status;
    private final Instant validFrom;
    private final Instant expiresAt;
    private final boolean revoked;
    private final Set<ApiPermission> permissions;
    private final String keyVersion;

    ControlPlaneCredentialRecord(
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
      this.alias = alias;
      this.principalType = principalType;
      this.keyMaterial = keyMaterial.clone();
      this.audience = audience;
      this.status = status;
      this.validFrom = validFrom;
      this.expiresAt = expiresAt;
      this.revoked = revoked;
      this.permissions = Set.copyOf(permissions);
      this.keyVersion = keyVersion;
    }

    String alias() {
      return alias;
    }

    String principalType() {
      return principalType;
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
      return new ControlPlanePrincipal(alias, keyVersion, principalType);
    }

    /**
     * Non-reversible SHA-256 fingerprint of the key material for test assertions. It is not equal
     * to the raw key hex and cannot be used to recover the secret.
     */
    String keyMaterialFingerprintForTests() {
      try {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(keyMaterial));
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("SHA-256 unavailable", e);
      }
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof ControlPlaneCredentialRecord that)) {
        return false;
      }
      return revoked == that.revoked
          && Objects.equals(alias, that.alias)
          && Objects.equals(principalType, that.principalType)
          && MessageDigest.isEqual(keyMaterial, that.keyMaterial)
          && Objects.equals(audience, that.audience)
          && Objects.equals(status, that.status)
          && Objects.equals(validFrom, that.validFrom)
          && Objects.equals(expiresAt, that.expiresAt)
          && Objects.equals(permissions, that.permissions)
          && Objects.equals(keyVersion, that.keyVersion);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          alias, principalType, audience, status, validFrom, expiresAt, revoked, permissions, keyVersion);
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
