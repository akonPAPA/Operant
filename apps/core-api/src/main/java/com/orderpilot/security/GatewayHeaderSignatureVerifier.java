package com.orderpilot.security;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Locale;

final class GatewayHeaderSignatureVerifier {
  static final String TENANT_HEADER = "X-Tenant-Id";
  static final String TIMESTAMP_HEADER = "X-OrderPilot-Gateway-Timestamp";
  static final String SIGNATURE_HEADER = "X-OrderPilot-Gateway-Signature";

  private final String sharedSecret;
  private final long maxSkewSeconds;
  private final Clock clock;

  GatewayHeaderSignatureVerifier(String sharedSecret, long maxSkewSeconds, Clock clock) {
    this.sharedSecret = sharedSecret == null ? "" : sharedSecret;
    this.maxSkewSeconds = maxSkewSeconds;
    this.clock = clock;
  }

  boolean verify(HttpServletRequest request) {
    if (sharedSecret.isBlank()) {
      return false;
    }
    String tenantId = requiredHeader(request, TENANT_HEADER);
    String actorId = requiredHeader(request, RequestActorResolver.ACTOR_HEADER);
    String permissions = requiredHeader(request, ApiPermissionGuard.PERMISSIONS_HEADER);
    String timestamp = requiredHeader(request, TIMESTAMP_HEADER);
    String signature = requiredHeader(request, SIGNATURE_HEADER);
    if (tenantId == null || actorId == null || permissions == null || timestamp == null || signature == null) {
      return false;
    }
    long timestampEpoch;
    try {
      timestampEpoch = Long.parseLong(timestamp);
    } catch (NumberFormatException ex) {
      return false;
    }
    long nowEpoch = clock.instant().getEpochSecond();
    if (Math.abs(nowEpoch - timestampEpoch) > maxSkewSeconds) {
      return false;
    }
    return SignedActorVerifier.matchesHmacHex(sharedSecret, canonical(request, tenantId, actorId, permissions,
        timestampEpoch), signature);
  }

  static String canonical(
      HttpServletRequest request,
      String tenantId,
      String actorId,
      String permissions,
      long timestampEpoch) {
    return request.getMethod().toUpperCase(Locale.ROOT) + "\n"
        + request.getRequestURI() + "\n"
        + tenantId.trim() + "\n"
        + actorId.trim() + "\n"
        + permissions.trim() + "\n"
        + timestampEpoch;
  }

  private static String requiredHeader(HttpServletRequest request, String name) {
    String value = request.getHeader(name);
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}
