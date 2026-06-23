package com.orderpilot.security;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Test-only contract fixture for the future trusted gateway/auth signer.
 *
 * <p>The fixture accepts only resolved trusted authority plus request facts needed by the backend
 * verifier. It intentionally has no public-client header or request-body authority input.
 */
final class TrustedGatewaySignerFixture {
  private final String sharedSecret;

  TrustedGatewaySignerFixture(String sharedSecret) {
    this.sharedSecret = sharedSecret;
  }

  SignedGatewayHeaders sign(
      TrustedGatewayContext context,
      ClientRequestFacts requestFacts,
      SigningFreshness freshness) {
    MockHttpServletRequest canonicalRequest =
        new MockHttpServletRequest(requestFacts.method(), requestFacts.path());
    String canonical = GatewayHeaderSignatureVerifier.canonical(
        canonicalRequest,
        context.tenantId(),
        context.actorId(),
        context.permissions(),
        freshness.timestampEpoch(),
        freshness.nonce());
    String signature = SignedActorVerifier.hmacHex(sharedSecret, canonical);

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put(GatewayHeaderSignatureVerifier.TENANT_HEADER, context.tenantId());
    headers.put(RequestActorResolver.ACTOR_HEADER, context.actorId());
    headers.put(ApiPermissionGuard.PERMISSIONS_HEADER, context.permissions());
    headers.put(GatewayHeaderSignatureVerifier.TIMESTAMP_HEADER, Long.toString(freshness.timestampEpoch()));
    headers.put(GatewayHeaderSignatureVerifier.NONCE_HEADER, freshness.nonce());
    headers.put(GatewayHeaderSignatureVerifier.SIGNATURE_HEADER, signature);
    return new SignedGatewayHeaders(requestFacts.method(), requestFacts.path(), headers);
  }

  record TrustedGatewayContext(String tenantId, String actorId, String permissions) {}

  record ClientRequestFacts(String method, String path) {}

  record SigningFreshness(long timestampEpoch, String nonce) {}

  record SignedGatewayHeaders(String method, String path, Map<String, String> headers) {
    SignedGatewayHeaders {
      headers = Map.copyOf(headers);
    }

    SignedGatewayHeaders withHeader(String name, String value) {
      Map<String, String> changed = new LinkedHashMap<>(headers);
      changed.put(name, value);
      return new SignedGatewayHeaders(method, path, changed);
    }

    SignedGatewayHeaders withoutHeader(String name) {
      Map<String, String> changed = new LinkedHashMap<>(headers);
      changed.remove(name);
      return new SignedGatewayHeaders(method, path, changed);
    }
  }
}
