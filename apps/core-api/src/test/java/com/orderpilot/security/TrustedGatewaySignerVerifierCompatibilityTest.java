package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;

class TrustedGatewaySignerVerifierCompatibilityTest {
  private static final String TEST_ONLY_SECRET = "op-cap-43h-test-only-gateway-contract-secret";
  private static final Instant NOW = Instant.parse("2026-06-23T00:00:00Z");
  private static final long MAX_SKEW_SECONDS = 300L;
  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "22222222-2222-2222-2222-222222222222";
  private static final String PATH = "/api/stage8/analytics/stage43h-signer-contract";

  private static final Path REPO_ROOT = Path.of("../..").normalize();
  private static final Path BOUNDARY_DOC =
      REPO_ROOT.resolve("docs/security/TRUSTED_GATEWAY_HEADER_BOUNDARY.md");
  private static final Path SIGNER_FIXTURE =
      Path.of("src/test/java/com/orderpilot/security/TrustedGatewaySignerFixture.java");

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final TrustedGatewaySignerFixture signer = new TrustedGatewaySignerFixture(TEST_ONLY_SECRET);

  @Test
  void signerFixtureProducesHeadersAcceptedByBackendVerifier() {
    assertThat(verifier().verify(request(signed("nonce-accepted")))).isTrue();
  }

  @Test
  void missingSignatureIsRejected() {
    var signed = signed("nonce-missing-signature")
        .withoutHeader(GatewayHeaderSignatureVerifier.SIGNATURE_HEADER);

    assertThat(verifier().verify(request(signed))).isFalse();
  }

  @Test
  void wrongSignatureIsRejected() {
    var signed = signed("nonce-wrong-signature")
        .withHeader(GatewayHeaderSignatureVerifier.SIGNATURE_HEADER, "deadbeef");

    assertThat(verifier().verify(request(signed))).isFalse();
  }

  @Test
  void staleTimestampIsRejected() {
    long staleTimestamp = NOW.getEpochSecond() - MAX_SKEW_SECONDS - 1L;
    var signed = signer.sign(authority(), requestFacts(),
        new TrustedGatewaySignerFixture.SigningFreshness(staleTimestamp, "nonce-stale-timestamp"));

    assertThat(verifier().verify(request(signed))).isFalse();
  }

  @Test
  void duplicateNonceIsRejectedThroughReplayAdmission() {
    GatewayHeaderSignatureVerifier verifier = verifier();
    var signed = signed("nonce-replayed");

    assertThat(verifier.verify(request(signed))).isTrue();
    assertThat(verifier.verify(request(signed))).isFalse();
  }

  @Test
  void changedTenantAfterSigningFails() {
    var signed = signed("nonce-tenant-tamper")
        .withHeader(GatewayHeaderSignatureVerifier.TENANT_HEADER, "99999999-9999-9999-9999-999999999999");

    assertThat(verifier().verify(request(signed))).isFalse();
  }

  @Test
  void changedActorAfterSigningFails() {
    var signed = signed("nonce-actor-tamper")
        .withHeader(RequestActorResolver.ACTOR_HEADER, "88888888-8888-8888-8888-888888888888");

    assertThat(verifier().verify(request(signed))).isFalse();
  }

  @Test
  void changedPermissionsAfterSigningFails() {
    var signed = signed("nonce-permission-tamper")
        .withHeader(ApiPermissionGuard.PERMISSIONS_HEADER, ApiPermission.ANALYTICS_MANAGE.name());

    assertThat(verifier().verify(request(signed))).isFalse();
  }

  @Test
  void publicClientAuthorityHeadersAreNotSignerInput() {
    Map<String, String> publicClientHeaders = Map.of(
        GatewayHeaderSignatureVerifier.TENANT_HEADER, "client-tenant",
        RequestActorResolver.ACTOR_HEADER, "client-actor",
        ApiPermissionGuard.PERMISSIONS_HEADER, ApiPermission.ANALYTICS_MANAGE.name());

    var signed = signed("nonce-trusted-context-only");

    assertThat(publicClientHeaders).containsValue("client-tenant");
    assertThat(signed.headers())
        .containsEntry(GatewayHeaderSignatureVerifier.TENANT_HEADER, TENANT)
        .containsEntry(RequestActorResolver.ACTOR_HEADER, ACTOR)
        .containsEntry(ApiPermissionGuard.PERMISSIONS_HEADER, ApiPermission.ANALYTICS_READ.name())
        .doesNotContainValue("client-tenant")
        .doesNotContainValue("client-actor")
        .doesNotContainValue(ApiPermission.ANALYTICS_MANAGE.name());
  }

  @Test
  void docsStateSignerAuthorityComesFromAuthenticatedGatewayContextNotRawClientHeaders()
      throws Exception {
    String doc = normalizeWhitespace(Files.readString(BOUNDARY_DOC));

    assertThat(doc)
        .contains("Trusted signer contract")
        .contains("authenticated identity, session, or API key context")
        .contains("must not come from public client headers, request bodies, AI workers, bots, or connectors")
        .contains("X-Tenant-Id")
        .contains("X-OrderPilot-Actor-Id")
        .contains("X-OrderPilot-Permissions")
        .contains("X-OrderPilot-Gateway-Timestamp")
        .contains("X-OrderPilot-Gateway-Nonce")
        .contains("X-OrderPilot-Gateway-Signature");
  }

  @Test
  void docsStateExactCanonicalStringAndCurrentQueryBodyLimitation() throws Exception {
    String doc = normalizeWhitespace(Files.readString(BOUNDARY_DOC));

    assertThat(doc)
        .contains("METHOD\\nREQUEST_URI_PATH\\ntenantId\\nactorId\\npermissions\\ntimestamp\\nnonce")
        .contains("does not include query string, request body, or body hash")
        .contains("Adding query/body binding is a separate production-code security slice");
  }

  @Test
  void signerContractArtifactsContainNoRealSecretMaterial() throws Exception {
    assertNoRealSecretMaterial(Files.readString(BOUNDARY_DOC));
    assertNoRealSecretMaterial(Files.readString(SIGNER_FIXTURE));
  }

  private static String normalizeWhitespace(String content) {
    return content.replaceAll("\\s+", " ").trim();
  }

  private static void assertNoRealSecretMaterial(String content) {
    assertThat(content)
        .doesNotContain("BEGIN PRIVATE KEY")
        .doesNotContain("BEGIN CERTIFICATE")
        .doesNotContain("ORDERPILOT_GATEWAY_HEADER_AUTH_SHARED_SECRET=")
        .doesNotContain("AKIA")
        .doesNotContain("password=")
        .doesNotContain("real secret")
        .doesNotContain("production secret");
  }

  private TrustedGatewaySignerFixture.SignedGatewayHeaders signed(String nonce) {
    return signer.sign(authority(), requestFacts(),
        new TrustedGatewaySignerFixture.SigningFreshness(NOW.getEpochSecond(), nonce));
  }

  private static TrustedGatewaySignerFixture.TrustedGatewayContext authority() {
    return new TrustedGatewaySignerFixture.TrustedGatewayContext(
        TENANT, ACTOR, ApiPermission.ANALYTICS_READ.name());
  }

  private static TrustedGatewaySignerFixture.ClientRequestFacts requestFacts() {
    return new TrustedGatewaySignerFixture.ClientRequestFacts(HttpMethod.GET.name(), PATH);
  }

  private GatewayHeaderSignatureVerifier verifier() {
    return new GatewayHeaderSignatureVerifier(
        TEST_ONLY_SECRET,
        MAX_SKEW_SECONDS,
        clock,
        new GatewayHeaderReplayGuard(clock, 100));
  }

  private static MockHttpServletRequest request(TrustedGatewaySignerFixture.SignedGatewayHeaders signed) {
    MockHttpServletRequest request = new MockHttpServletRequest(signed.method(), signed.path());
    signed.headers().forEach(request::addHeader);
    return request;
  }
}
