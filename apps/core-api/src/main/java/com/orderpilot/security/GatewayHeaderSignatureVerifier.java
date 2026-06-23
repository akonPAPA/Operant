package com.orderpilot.security;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

final class GatewayHeaderSignatureVerifier {
  static final String TENANT_HEADER = "X-Tenant-Id";
  static final String TIMESTAMP_HEADER = "X-OrderPilot-Gateway-Timestamp";
  static final String SIGNATURE_HEADER = "X-OrderPilot-Gateway-Signature";
  // OP-CAP-43E: per-request single-use nonce. The gateway must generate a unique value per signed
  // request and bind it into the HMAC canonical string; the backend admits each value at most once.
  static final String NONCE_HEADER = "X-OrderPilot-Gateway-Nonce";

  private final String sharedSecret;
  private final long maxSkewSeconds;
  private final Clock clock;
  private final GatewayHeaderReplayAdmissionStore replayAdmissionStore;

  GatewayHeaderSignatureVerifier(
      String sharedSecret,
      long maxSkewSeconds,
      Clock clock,
      GatewayHeaderReplayAdmissionStore replayAdmissionStore) {
    this.sharedSecret = sharedSecret == null ? "" : sharedSecret;
    this.maxSkewSeconds = maxSkewSeconds;
    this.clock = clock;
    this.replayAdmissionStore = replayAdmissionStore;
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
    String nonce = requiredHeader(request, NONCE_HEADER);
    if (tenantId == null || actorId == null || permissions == null || timestamp == null
        || signature == null || nonce == null) {
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
    if (!SignedActorVerifier.matchesHmacHex(sharedSecret,
        canonical(request, tenantId, actorId, permissions, timestampEpoch, nonce), signature)) {
      return false;
    }
    // Single-use admission runs only after the signature and freshness checks pass, so a forged or
    // tampered request can neither authenticate nor consume a legitimate gateway nonce slot. A replay
    // of the same fresh, signed request is rejected here even though its signature is valid.
    return replayAdmissionStore.admitFirstUse(
        tenantId, actorId, nonce, Duration.ofSeconds(Math.max(1L, maxSkewSeconds * 2)));
  }

  static String canonical(
      HttpServletRequest request,
      String tenantId,
      String actorId,
      String permissions,
      long timestampEpoch,
      String nonce) {
    return request.getMethod().toUpperCase(Locale.ROOT) + "\n"
        + request.getRequestURI() + "\n"
        + tenantId.trim() + "\n"
        + actorId.trim() + "\n"
        + permissions.trim() + "\n"
        + timestampEpoch + "\n"
        + nonce.trim();
  }

  private static String requiredHeader(HttpServletRequest request, String name) {
    String value = request.getHeader(name);
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}
