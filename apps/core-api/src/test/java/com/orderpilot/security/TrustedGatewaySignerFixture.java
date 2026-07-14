package com.orderpilot.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Test-only contract fixture for the trusted gateway/auth signer (signature version 2).
 *
 * <p>The fixture accepts only resolved trusted authority plus request facts needed by the backend
 * verifier. It intentionally has no public-client header or request-body authority input.
 */
final class TrustedGatewaySignerFixture {
  private final byte[] sharedSecretKey;

  TrustedGatewaySignerFixture(String sharedSecretHex) {
    this.sharedSecretKey = GatewayHmacKeyCodec.requireValid("test-gateway-secret", sharedSecretHex);
  }

  SignedGatewayHeaders sign(
      TrustedGatewayContext context,
      ClientRequestFacts requestFacts,
      SigningFreshness freshness) {
    String bodySha = GatewayV2Canonical.sha256Hex(requestFacts.bodyBytes());
    String contentType = requestFacts.bodyBytes().length == 0 ? "" : requestFacts.contentType();
    String canonical = GatewayV2Canonical.build(
        requestFacts.method(),
        requestFacts.path(),
        requestFacts.rawQuery(),
        contentType,
        bodySha,
        context.tenantId(),
        context.actorId(),
        context.permissions(),
        freshness.timestampEpoch(),
        freshness.nonce());
    String signature = SignedActorVerifier.hmacHex(sharedSecretKey, canonical);

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put(GatewayHeaderSignatureVerifier.TENANT_HEADER, context.tenantId());
    headers.put(RequestActorResolver.ACTOR_HEADER, context.actorId());
    headers.put(ApiPermissionGuard.PERMISSIONS_HEADER, context.permissions());
    headers.put(GatewayHeaderSignatureVerifier.TIMESTAMP_HEADER, Long.toString(freshness.timestampEpoch()));
    headers.put(GatewayHeaderSignatureVerifier.NONCE_HEADER, freshness.nonce());
    headers.put(GatewayHeaderSignatureVerifier.VERSION_HEADER, GatewayV2Canonical.SIGNATURE_VERSION);
    headers.put(GatewayHeaderSignatureVerifier.CONTENT_SHA256_HEADER, bodySha);
    headers.put(GatewayHeaderSignatureVerifier.SIGNATURE_HEADER, signature);
    return new SignedGatewayHeaders(
        requestFacts.method(),
        requestFacts.path(),
        requestFacts.rawQuery(),
        requestFacts.bodyBytes(),
        contentType,
        headers);
  }

  record TrustedGatewayContext(String tenantId, String actorId, String permissions) {}

  record ClientRequestFacts(String method, String path, String rawQuery, String contentType, byte[] bodyBytes) {
    ClientRequestFacts(String method, String path) {
      this(method, path, "", "", new byte[0]);
    }

    ClientRequestFacts {
      rawQuery = rawQuery == null ? "" : rawQuery;
      contentType = contentType == null ? "" : contentType;
      bodyBytes = bodyBytes == null ? new byte[0] : bodyBytes;
    }
  }

  record SigningFreshness(long timestampEpoch, String nonce) {}

  record SignedGatewayHeaders(
      String method,
      String path,
      String rawQuery,
      byte[] bodyBytes,
      String contentType,
      Map<String, String> headers) {
    SignedGatewayHeaders {
      headers = Map.copyOf(headers);
      bodyBytes = bodyBytes == null ? new byte[0] : bodyBytes.clone();
      rawQuery = rawQuery == null ? "" : rawQuery;
      contentType = contentType == null ? "" : contentType;
    }

    SignedGatewayHeaders withHeader(String name, String value) {
      Map<String, String> changed = new LinkedHashMap<>(headers);
      changed.put(name, value);
      return new SignedGatewayHeaders(method, path, rawQuery, bodyBytes, contentType, changed);
    }

    SignedGatewayHeaders withoutHeader(String name) {
      Map<String, String> changed = new LinkedHashMap<>(headers);
      changed.remove(name);
      return new SignedGatewayHeaders(method, path, rawQuery, bodyBytes, contentType, changed);
    }

    SignedGatewayHeaders withBody(byte[] newBody) {
      return new SignedGatewayHeaders(method, path, rawQuery, newBody, contentType, headers);
    }
  }

  static HttpServletRequest toCachedRequest(SignedGatewayHeaders signed) {
    MockHttpServletRequest request = new MockHttpServletRequest(signed.method(), signed.path());
    if (!signed.rawQuery().isEmpty()) {
      request.setQueryString(signed.rawQuery());
    }
    if (!signed.contentType().isEmpty()) {
      request.addHeader("Content-Type", signed.contentType());
    }
    signed.headers().forEach(request::addHeader);
    request.setContent(signed.bodyBytes());
    return new CachedBodyHttpServletRequest(request, signed.bodyBytes());
  }
}
