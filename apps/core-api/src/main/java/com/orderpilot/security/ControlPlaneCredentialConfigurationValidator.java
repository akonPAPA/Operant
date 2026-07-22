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

public final class ControlPlaneCredentialConfigurationValidator {
  private static final Pattern ALIAS_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");
  private static final Pattern KEY_VERSION_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,31}$");
  private static final int FAR_FUTURE_YEAR = 9999;

  private ControlPlaneCredentialConfigurationValidator() {}

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
    String resolvedStatus = parseStatus(status, strict);
    if (ControlPlaneCredentialRegistry.STATUS_DISABLED.equals(resolvedStatus)
        || ControlPlaneCredentialRegistry.STATUS_REVOKED.equals(resolvedStatus)) {
      return Optional.empty();
    }
    try {
      return Optional.of(enabledRecord(
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
      throw invalid("orderpilot.security.control-plane-auth.status");
    }
    String resolvedAlias = requireExact("orderpilot.security.control-plane-auth.credential-alias", alias);
    if (!ALIAS_PATTERN.matcher(resolvedAlias).matches()) {
      throw invalid("orderpilot.security.control-plane-auth.credential-alias");
    }
    byte[] controlKey = GatewayHmacKeyCodec.requireValid(
        "orderpilot.security.control-plane-auth.shared-secret", sharedSecret);
    try {
      rejectGatewayKeyReuse(controlKey, gatewaySharedSecret);
      String resolvedAudience = requireExact("orderpilot.security.control-plane-auth.audience", audience);
      if (!ControlPlaneProtocol.AUDIENCE.equals(resolvedAudience)) {
        throw invalid("orderpilot.security.control-plane-auth.audience");
      }
      if (revoked) {
        throw invalid("orderpilot.security.control-plane-auth.revoked");
      }
      Instant resolvedValidFrom = requireInstant("orderpilot.security.control-plane-auth.valid-from", validFrom);
      Instant resolvedExpiresAt = requireInstant("orderpilot.security.control-plane-auth.expires-at", expiresAt);
      if (!resolvedExpiresAt.isAfter(resolvedValidFrom)) {
        throw invalid("orderpilot.security.control-plane-auth.expires-at");
      }
      if (!resolvedExpiresAt.isAfter(clock.instant())) {
        throw invalid("orderpilot.security.control-plane-auth.expires-at");
      }
      if (resolvedExpiresAt.atZone(java.time.ZoneOffset.UTC).getYear() >= FAR_FUTURE_YEAR) {
        throw invalid("orderpilot.security.control-plane-auth.expires-at");
      }
      Set<ApiPermission> resolvedPermissions = requirePermissions(permissions);
      String resolvedKeyVersion = requireExact("orderpilot.security.control-plane-auth.key-version", keyVersion);
      if (!KEY_VERSION_PATTERN.matcher(resolvedKeyVersion).matches()) {
        throw invalid("orderpilot.security.control-plane-auth.key-version");
      }
      return new ControlPlaneCredentialRegistry.ControlPlaneCredentialRecord(
          resolvedAlias,
          ControlPlaneCredentialRegistry.PRINCIPAL_TYPE,
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

  private static String parseStatus(String raw, boolean strict) {
    if (raw == null || raw.isBlank()) {
      return ControlPlaneCredentialRegistry.STATUS_DISABLED;
    }
    if (!raw.equals(raw.trim())) {
      if (strict) {
        throw invalid("orderpilot.security.control-plane-auth.status");
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
      throw invalid("orderpilot.security.control-plane-auth.status");
    }
    return "__INVALID__";
  }

  private static void rejectGatewayKeyReuse(byte[] controlKey, String gatewaySharedSecret) {
    byte[] gatewayKey = GatewayHmacKeyCodec.tryDecode(gatewaySharedSecret);
    if (gatewayKey == null) {
      return;
    }
    try {
      if (MessageDigest.isEqual(controlKey, gatewayKey)) {
        throw invalid("orderpilot.security.control-plane-auth.shared-secret");
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

  private static Set<ApiPermission> requirePermissions(String raw) {
    String configured = requireExact("orderpilot.security.control-plane-auth.permissions", raw);
    EnumSet<ApiPermission> permissions = EnumSet.noneOf(ApiPermission.class);
    boolean hasStaffControl = false;
    boolean hasExecutor = false;
    for (String value : configured.split(",", -1)) {
      if (value.isBlank() || !value.equals(value.trim())) {
        throw invalid("orderpilot.security.control-plane-auth.permissions");
      }
      try {
        ApiPermission permission = ApiPermission.valueOf(value);
        boolean staffControl = permission.name().startsWith("STAFF_CONTROL_");
        boolean executor = permission.name().startsWith("CONTROL_EXECUTOR_");
        if (!staffControl && !executor) {
          throw invalid("orderpilot.security.control-plane-auth.permissions");
        }
        hasStaffControl |= staffControl;
        hasExecutor |= executor;
        permissions.add(permission);
      } catch (IllegalArgumentException unknown) {
        throw invalid("orderpilot.security.control-plane-auth.permissions");
      }
    }
    if (permissions.isEmpty()) {
      throw invalid("orderpilot.security.control-plane-auth.permissions");
    }
    // P1-E2A principal-class separation: a single control credential is EITHER the human staff-control
    // principal (STAFF_CONTROL_*) OR the machine lifecycle executor (CONTROL_EXECUTOR_*), never both. This
    // makes "staff credentials must not act as executors" (and the converse) a structural config invariant,
    // not merely a route-mapping property.
    if (hasStaffControl && hasExecutor) {
      throw invalid("orderpilot.security.control-plane-auth.permissions");
    }
    return Set.copyOf(permissions);
  }

  private static IllegalArgumentException invalid(String propertyName) {
    return new IllegalArgumentException(propertyName + " is invalid for an enabled control credential");
  }
}
