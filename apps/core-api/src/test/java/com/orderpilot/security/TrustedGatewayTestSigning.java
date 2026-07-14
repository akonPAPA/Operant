package com.orderpilot.security;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Shared v2 signing helpers for gateway security tests (decoded 32-byte key). */
final class TrustedGatewayTestSigning {
  private TrustedGatewayTestSigning() {}

  static MockHttpServletRequestBuilder signed(
      String sharedSecretHex,
      HttpMethod method,
      String path,
      String tenant,
      String actor,
      String permissions,
      long timestampEpoch,
      String nonce,
      String rawQuery,
      String contentType,
      byte[] bodyBytes) {
    byte[] key = GatewayHmacKeyCodec.requireValid("test-secret", sharedSecretHex);
    byte[] body = bodyBytes == null ? new byte[0] : bodyBytes;
    String bodySha = GatewayV2Canonical.sha256Hex(body);
    String effectiveContentType = body.length == 0 ? "" : contentType;
    String canonical = GatewayV2Canonical.build(
        method.name(),
        path,
        rawQuery == null ? "" : rawQuery,
        effectiveContentType,
        bodySha,
        tenant,
        actor,
        permissions,
        timestampEpoch,
        nonce);
    String signature = SignedActorVerifier.hmacHex(key, canonical);
    MockHttpServletRequestBuilder builder =
        switch (method.name()) {
          case "POST" -> MockMvcRequestBuilders.post(queryPath(path, rawQuery));
          case "PUT" -> MockMvcRequestBuilders.put(queryPath(path, rawQuery));
          case "PATCH" -> MockMvcRequestBuilders.patch(queryPath(path, rawQuery));
          case "DELETE" -> MockMvcRequestBuilders.delete(queryPath(path, rawQuery));
          default -> MockMvcRequestBuilders.get(queryPath(path, rawQuery));
        };
    builder
        .header(GatewayHeaderSignatureVerifier.TENANT_HEADER, tenant)
        .header(RequestActorResolver.ACTOR_HEADER, actor)
        .header(ApiPermissionGuard.PERMISSIONS_HEADER, permissions)
        .header(GatewayHeaderSignatureVerifier.TIMESTAMP_HEADER, Long.toString(timestampEpoch))
        .header(GatewayHeaderSignatureVerifier.NONCE_HEADER, nonce)
        .header(GatewayHeaderSignatureVerifier.VERSION_HEADER, GatewayV2Canonical.SIGNATURE_VERSION)
        .header(GatewayHeaderSignatureVerifier.CONTENT_SHA256_HEADER, bodySha)
        .header(GatewayHeaderSignatureVerifier.SIGNATURE_HEADER, signature);
    if (body.length > 0) {
      builder.contentType(effectiveContentType).content(body);
    }
    return builder;
  }

  static MockHttpServletRequestBuilder signedGet(
      String sharedSecretHex,
      String path,
      String tenant,
      String actor,
      String permissions,
      long timestampEpoch) {
    return signed(
        sharedSecretHex,
        HttpMethod.GET,
        path,
        tenant,
        actor,
        permissions,
        timestampEpoch,
        UUID.randomUUID().toString(),
        "",
        "",
        new byte[0]);
  }

  static String hmacV2EmptyBody(
      String sharedSecretHex,
      String method,
      String path,
      String tenant,
      String actor,
      String permissions,
      long timestampEpoch,
      String nonce) {
    byte[] key = GatewayHmacKeyCodec.requireValid("test-secret", sharedSecretHex);
    String canonical = GatewayV2Canonical.build(
        method,
        path,
        "",
        "",
        GatewayV2Canonical.EMPTY_BODY_SHA256_HEX,
        tenant,
        actor,
        permissions,
        timestampEpoch,
        nonce);
    return SignedActorVerifier.hmacHex(key, canonical);
  }

  private static String queryPath(String path, String rawQuery) {
    if (rawQuery == null || rawQuery.isEmpty()) {
      return path;
    }
    return path + "?" + rawQuery;
  }
}
