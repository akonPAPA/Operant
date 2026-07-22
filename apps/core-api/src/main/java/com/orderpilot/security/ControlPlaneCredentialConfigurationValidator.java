package com.orderpilot.security;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates bounded control-plane credential slots without exposing key material. */
public final class ControlPlaneCredentialConfigurationValidator {
  private static final String ROOT_PREFIX = "orderpilot.security.control-plane-auth";
  private static final Pattern PRINCIPAL_ID_PATTERN =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$");
  private static final Pattern ALIAS_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");
  private static final Pattern KEY_VERSION_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,31}$");
  private static final int FAR_FUTURE_YEAR = 9999;

  private ControlPlaneCredentialConfigurationValidator() {}

  /**
   * Compatibility validation for the original single control credential. Enabled credentials infer their
   * typed principal class from the one allowed permission family and use the server-configured alias as
   * the stable logical principal id.
   */
  public static void validateProductionConfiguration(
      String alias,
      String sharedSecret,
      String audience,
      String status,
      String validFrom,
      String expiresAt,
      boolean revoked,
      String permissions,
      String keyVersion,
      String gatewaySharedSecret,
      Clock clock) {
    validatedRecord(
        alias,
        sharedSecret,
        audience,
        status,
        validFrom,
        expiresAt,
        revoked,
        permissions,
        keyVersion,
        gatewaySharedSecret,
        clock,
        true);
  }

  /** Strict production validation for one explicit typed credential slot. */
  public static void validateProductionCredentialSlot(
      String propertyPrefix,
      String principalId,
      ControlPlanePrincipalType principalType,
      String alias,
      String sharedSecret,
      String audience,
      String status,
      String validFrom,
      String expiresAt,
      boolean revoked,
      String permissions,
      String keyVersion,
      String gatewaySharedSecret,
      Clock clock) {
    validatedRecordForSlot(
        propertyPrefix,
        principalId,
        principalType,
        alias,
        sharedSecret,
        audience,
        status,
        validFrom,
        expiresAt,
        revoked,
        permissions,
        keyVersion,
        gatewaySharedSecret,
        clock,
        true);
  }

  static Optional<ControlPlaneCredentialRegistry.ControlPlaneCredentialRecord> validatedRecord(
      String alias,
      String sharedSecret,
      String audience,
      String status,
      String validFrom,
      String expiresAt,
      boolean revoked,
      String permissions,
      String keyVersion,
      String gatewaySharedSecret,
      Clock clock,
      boolean strict) {
    String resolvedStatus = parseStatus(ROOT_PREFIX, status, strict);
    if (isInactive(resolvedStatus)) {
      return Optional.empty();
    }
    try {
      ControlPlanePrincipalType inferredType = inferPrincipalType(ROOT_PREFIX, permissions);
      return Optional.of(enabledRecord(
          ROOT_PREFIX,
          alias,
          inferredType,
          alias,
          sharedSecret,
          audience,
          resolvedStatus,
          validFrom,
          expiresAt,
          revoked,
          permissions,
          keyVersion,
          gatewaySharedSecret,
          clock));
    } catch (IllegalArgumentException invalid) {
      if (strict) {
        throw invalid;
      }
      return Optional.empty();
    }
  }

  static Optional<ControlPlaneCredentialRegistry.ControlPlaneCredentialRecord> validatedRecordForSlot(
      String propertyPrefix,
      String principalId,
      ControlPlanePrincipalType principalType,
      String alias,
      String sharedSecret,
      String audience,
      String status,
      String validFrom,
      String expiresAt,
      boolean revoked,
      String permissions,
      String keyVersion,
      String gatewaySharedSecret,
      Clock clock,
      boolean strict) {
    String resolvedPrefix = requirePropertyPrefix(propertyPrefix);
    String resolvedStatus = parseStatus(resolvedPrefix, status, strict);
    if (isInactive(resolvedStatus)) {
      return Optional.empty();
    }
    try {
      return Optional.of(enabledRecord(
          resolvedPrefix,
          principalId,
          principalType,
          alias,
          sharedSecret,
          audience,
          resolvedStatus,
          validFrom,
          expiresAt,
          revoked,
          permissions,
          keyVersion,
          gatewaySharedSecret,
          clock));
    } catch (IllegalArgumentException invalid) {
      if (strict) {
        throw invalid;
      }
      return Optional.empty();
    }
  }

  private static ControlPlaneCredentialRegistry.ControlPlaneCredentialRecord enabledRecord(
      String prefix,
      String principalId,
      ControlPlanePrincipalType principalType,
      String alias,
      String sharedSecret,
      String audience,
      String status,
      String validFrom,
      String expiresAt,
      boolean revoked,
      String permissions,
      String keyVersion,
      String gatewaySharedSecret,
      Clock clock) {
    if (!ControlPlaneCredentialRegistry.STATUS_ENABLED.equals(status)) {
      throw invalid(prefix + ".status");
    }
    if (principalType == null) {
      throw invalid(prefix + ".principal-type");
    }
    String resolvedPrincipalId = requireExact(prefix + ".principal-id", principalId);
    if (!PRINCIPAL_ID_PATTERN.matcher(resolvedPrincipalId).matches()) {
      throw invalid(prefix + ".principal-id");
    }
    String resolvedAlias = requireExact(prefix + ".credential-alias", alias);
    if (!ALIAS_PATTERN.matcher(resolvedAlias).matches()) {
      throw invalid(prefix + ".credential-alias");
    }
    byte[] controlKey = GatewayHmacKeyCodec.requireValid(prefix + ".shared-secret", sharedSecret);
    try {
      rejectGatewayKeyReuse(prefix, controlKey, gatewaySharedSecret);
      String resolvedAudience = requireExact(prefix + ".audience", audience);
      if (!ControlPlaneProtocol.AUDIENCE.equals(resolvedAudience)) {
        throw invalid(prefix + ".audience");
      }
      if (revoked) {
        throw invalid(prefix + ".revoked");
      }
      Instant resolvedValidFrom = requireInstant(prefix + ".valid-from", validFrom);
      Instant resolvedExpiresAt = requireInstant(prefix + ".expires-at", expiresAt);
      if (!resolvedExpiresAt.isAfter(resolvedValidFrom)
          || !resolvedExpiresAt.isAfter(clock.instant())
          || resolvedExpiresAt.atZone(java.time.ZoneOffset.UTC).getYear() >= FAR_FUTURE_YEAR) {
        throw invalid(prefix + ".expires-at");
      }
      Set<ApiPermission> resolvedPermissions = requirePermissions(prefix, permissions, principalType);
      String resolvedKeyVersion = requireExact(prefix + ".key-version", keyVersion);
      if (!KEY_VERSION_PATTERN.matcher(resolvedKeyVersion).matches()) {
        throw invalid(prefix + ".key-version");
      }
      return new ControlPlaneCredentialRegistry.ControlPlaneCredentialRecord(
          resolvedPrincipalId,
          principalType,
          resolvedAlias,
          controlKey,
          resolvedAudience,
          status,
          resolvedValidFrom,
          resolvedExpiresAt,
          false,
          resolvedPermissions,
          resolvedKeyVersion);
    } finally {
      Arrays.fill(controlKey, (byte) 0);
    }
  }

  private static boolean isInactive(String status) {
    return ControlPlaneCredentialRegistry.STATUS_DISABLED.equals(status)
        || ControlPlaneCredentialRegistry.STATUS_REVOKED.equals(status);
  }

  private static String parseStatus(String prefix, String raw, boolean strict) {
    if (raw == null || raw.isBlank()) {
      return ControlPlaneCredentialRegistry.STATUS_DISABLED;
    }
    if (!raw.equals(raw.trim())) {
      if (strict) {
        throw invalid(prefix + ".status");
      }
      return "__INVALID__";
    }
    String upper = raw.toUpperCase(Locale.ROOT);
    if (ControlPlaneCredentialRegistry.STATUS_ENABLED.equals(upper)
        || ControlPlaneCredentialRegistry.STATUS_DISABLED.equals(upper)
        || ControlPlaneCredentialRegistry.STATUS_REVOKED.equals(upper)) {
      return upper;
    }
    if (strict) {
      throw invalid(prefix + ".status");
    }
    return "__INVALID__";
  }

  private static ControlPlanePrincipalType inferPrincipalType(String prefix, String rawPermissions) {
    String configured = requireExact(prefix + ".permissions", rawPermissions);
    ControlPlanePrincipalType inferred = null;
    for (String value : configured.split(",", -1)) {
      if (value.isBlank() || !value.equals(value.trim())) {
        throw invalid(prefix + ".permissions");
      }
      ApiPermission permission;
      try {
        permission = ApiPermission.valueOf(value);
      } catch (IllegalArgumentException unknown) {
        throw invalid(prefix + ".permissions");
      }
      ControlPlanePrincipalType candidate = null;
      for (ControlPlanePrincipalType type : ControlPlanePrincipalType.values()) {
        if (type.permits(permission)) {
          candidate = type;
          break;
        }
      }
      if (candidate == null || (inferred != null && inferred != candidate)) {
        throw invalid(prefix + ".permissions");
      }
      inferred = candidate;
    }
    if (inferred == null) {
      throw invalid(prefix + ".permissions");
    }
    return inferred;
  }

  private static Set<ApiPermission> requirePermissions(
      String prefix,
      String raw,
      ControlPlanePrincipalType principalType) {
    String configured = requireExact(prefix + ".permissions", raw);
    EnumSet<ApiPermission> permissions = EnumSet.noneOf(ApiPermission.class);
    for (String value : configured.split(",", -1)) {
      if (value.isBlank() || !value.equals(value.trim())) {
        throw invalid(prefix + ".permissions");
      }
      try {
        ApiPermission permission = ApiPermission.valueOf(value);
        if (!principalType.permits(permission)) {
          throw invalid(prefix + ".permissions");
        }
        permissions.add(permission);
      } catch (IllegalArgumentException unknown) {
        throw invalid(prefix + ".permissions");
      }
    }
    if (permissions.isEmpty()) {
      throw invalid(prefix + ".permissions");
    }
    return Set.copyOf(permissions);
  }

  private static void rejectGatewayKeyReuse(
      String prefix,
      byte[] controlKey,
      String gatewaySharedSecret) {
    byte[] gatewayKey = GatewayHmacKeyCodec.tryDecode(gatewaySharedSecret);
    if (gatewayKey == null) {
      return;
    }
    try {
      if (MessageDigest.isEqual(controlKey, gatewayKey)) {
        throw invalid(prefix + ".shared-secret");
      }
    } finally {
      Arrays.fill(gatewayKey, (byte) 0);
    }
  }

  private static String requireExact(String propertyName, String value) {
    if (value == null || value.isBlank() || !value.equals(value.trim())) {
      throw invalid(propertyName);
    }
    return value;
  }

  private static Instant requireInstant(String propertyName, String raw) {
    String value = requireExact(propertyName, raw);
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException invalid) {
      throw invalid(propertyName);
    }
  }

  private static String requirePropertyPrefix(String prefix) {
    if (prefix == null || prefix.isBlank() || !prefix.equals(prefix.trim())) {
      throw new IllegalArgumentException("control-plane credential property prefix is invalid");
    }
    return prefix;
  }

  private static IllegalArgumentException invalid(String propertyName) {
    return new IllegalArgumentException(propertyName + " is invalid for an enabled control credential");
  }
}
