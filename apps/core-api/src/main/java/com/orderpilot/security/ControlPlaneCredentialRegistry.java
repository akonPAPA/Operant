package com.orderpilot.security;

import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
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

    ControlPlanePrincipal principal() {
      return new ControlPlanePrincipal(alias, keyVersion, principalType);
    }

    String keyMaterialFingerprintForTests() {
      return HexFormat.of().formatHex(keyMaterial);
    }
  }
}
