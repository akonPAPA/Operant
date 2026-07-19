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
  static final String VERSION_HEADER = GatewayV2Canonical.VERSION_HEADER;
  static final String CONTENT_SHA256_HEADER = GatewayV2Canonical.CONTENT_SHA256_HEADER;
  // P1-E: the historical gateway "control" key selector is retired. Control-plane credentials use
  // the separate OPERANT_CONTROL_V1 protocol; any request that tries to mint STAFF_CONTROL_* through
  // the browser-gateway signature path fails closed.
  static final String KEY_ID_HEADER = "X-OrderPilot-Gateway-Key";
  static final String KEY_ID_GATEWAY = "gateway";
  static final String KEY_ID_CONTROL = "control";
  private static final String CONTROL_PERMISSION_PREFIX = "STAFF_CONTROL_";

  private final byte[] sharedSecretKey;
  private final long maxSkewSeconds;
  private final Clock clock;
  private final GatewayHeaderReplayAdmissionStore replayAdmissionStore;

  GatewayHeaderSignatureVerifier(
      String sharedSecret,
      long maxSkewSeconds,
      Clock clock,
      GatewayHeaderReplayAdmissionStore replayAdmissionStore) {
    this.sharedSecretKey = GatewayHmacKeyCodec.tryDecode(sharedSecret);
    this.maxSkewSeconds = maxSkewSeconds;
    this.clock = clock;
    this.replayAdmissionStore = replayAdmissionStore;
  }

  boolean verify(HttpServletRequest request) {
    byte[] verifyingKey = verifyingKeyFor(request);
    if (verifyingKey == null) {
      return false;
    }
    String tenantId = requiredHeader(request, TENANT_HEADER);
    String actorId = requiredHeader(request, RequestActorResolver.ACTOR_HEADER);
    String permissions = requiredHeader(request, ApiPermissionGuard.PERMISSIONS_HEADER);
    String timestamp = requiredHeader(request, TIMESTAMP_HEADER);
    String signature = requiredHeader(request, SIGNATURE_HEADER);
    String nonce = requiredHeader(request, NONCE_HEADER);
    String version = requiredHeader(request, VERSION_HEADER);
    String contentShaHeader = requiredHeader(request, CONTENT_SHA256_HEADER);
    if (tenantId == null
        || actorId == null
        || permissions == null
        || timestamp == null
        || signature == null
        || nonce == null
        || version == null
        || contentShaHeader == null) {
      return false;
    }
    if (!GatewayV2Canonical.SIGNATURE_VERSION.equals(version)) {
      return false;
    }
    if (!GatewayV2Canonical.isValidContentSha256Hex(contentShaHeader)) {
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

    byte[] bodyBytes = bodyBytes(request);
    if (bodyBytes == null) {
      // Fail closed: content-hash verification requires a reusable cached body wrapper.
      return false;
    }
    String actualBodySha = GatewayV2Canonical.sha256Hex(bodyBytes);
    if (!constantTimeHexEquals(actualBodySha, contentShaHeader)) {
      return false;
    }

    String contentType = normalizedContentType(request, bodyBytes.length);
    if (contentType == null) {
      return false;
    }

    String path = request.getRequestURI();
    String rawQuery = request.getQueryString() == null ? "" : request.getQueryString();
    String canonical;
    try {
      canonical = GatewayV2Canonical.build(
          request.getMethod(),
          path,
          rawQuery,
          contentType,
          actualBodySha,
          tenantId,
          actorId,
          permissions,
          timestampEpoch,
          nonce);
    } catch (IllegalArgumentException ex) {
      return false;
    }
    if (!SignedActorVerifier.matchesHmacHex(verifyingKey, canonical, signature)) {
      return false;
    }
    if (!permissionsAllowedForGateway(permissions)) {
      return false;
    }
    // Single-use admission runs only after the signature and freshness checks pass, so a forged or
    // tampered request can neither authenticate nor consume a legitimate gateway nonce slot. A replay
    // of the same fresh, signed request is rejected here even though its signature is valid.
    return replayAdmissionStore.admitFirstUse(
        tenantId, actorId, nonce, Duration.ofSeconds(Math.max(1L, maxSkewSeconds * 2)));
  }

  /** Resolve the verifying secret from the key-id header; unknown or unconfigured keys fail closed. */
  private byte[] verifyingKeyFor(HttpServletRequest request) {
    String keyId = request.getHeader(KEY_ID_HEADER);
    if (keyId == null || keyId.isBlank() || KEY_ID_GATEWAY.equals(keyId)) {
      return sharedSecretKey;
    }
    if (KEY_ID_CONTROL.equals(keyId)) {
      return null;
    }
    return null;
  }

  private boolean permissionsAllowedForGateway(String permissions) {
    for (String permission : permissions.split(",")) {
      String trimmed = permission.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      if (trimmed.startsWith(CONTROL_PERMISSION_PREFIX)) {
        return false;
      }
    }
    return true;
  }

  /**
   * @deprecated retained only for source inspection of the historical v1 contract; production uses
   *     {@link GatewayV2Canonical#build}.
   */
  @Deprecated
  static String canonical(
      HttpServletRequest request,
      String tenantId,
      String actorId,
      String permissions,
      long timestampEpoch,
      String nonce) {
    // Compatibility shim used by older unit tests during migration - rebuilds v2 empty-body form.
    String rawQuery = request.getQueryString() == null ? "" : request.getQueryString();
    return GatewayV2Canonical.build(
        request.getMethod(),
        request.getRequestURI(),
        rawQuery,
        "",
        GatewayV2Canonical.EMPTY_BODY_SHA256_HEX,
        tenantId,
        actorId,
        permissions,
        timestampEpoch,
        nonce);
  }

  private static byte[] bodyBytes(HttpServletRequest request) {
    if (request instanceof CachedBodyHttpServletRequest cached) {
      return cached.cachedBody();
    }
    // Fail closed: without a reusable cached body the content hash cannot be verified safely.
    return null;
  }

  private static String normalizedContentType(HttpServletRequest request, int bodyLength) {
    String raw = request.getHeader("Content-Type");
    if (raw == null || raw.isBlank()) {
      return bodyLength == 0 ? "" : null;
    }
    // Reject ambiguous duplicated Content-Type headers.
    var values = request.getHeaders("Content-Type");
    int count = 0;
    while (values != null && values.hasMoreElements()) {
      values.nextElement();
      count++;
      if (count > 1) {
        return null;
      }
    }
    String normalized = GatewayV2Canonical.normalizeContentType(raw);
    if (bodyLength > 0 && !"application/json".equals(normalized)) {
      return null;
    }
    if (bodyLength == 0 && !normalized.isEmpty() && !"application/json".equals(normalized)) {
      return null;
    }
    if (bodyLength == 0) {
      return "";
    }
    return normalized;
  }

  private static boolean constantTimeHexEquals(String left, String right) {
    if (left == null || right == null) {
      return false;
    }
    byte[] a = left.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    byte[] b = right.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    return java.security.MessageDigest.isEqual(a, b);
  }

  private static String requiredHeader(HttpServletRequest request, String name) {
    String value = request.getHeader(name);
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}
