package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;

class TrustedGatewaySignerVerifierCompatibilityTest {
  private static final String TEST_ONLY_SECRET =
      "a3f91c7e2b4d8056e1a9c0d4f7b26385e6a1d9c2b4f70835a6e9c1d2b3f40517";
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
  private static final Path CROSS_LANGUAGE_FIXTURE =
      REPO_ROOT.resolve("docs/security/gateway-signature-fixture.json");

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
  void bodyByteTamperAfterSigningFails() {
    var signed = signed("nonce-body-tamper").withBody("tampered".getBytes(StandardCharsets.UTF_8));
    assertThat(verifier().verify(request(signed))).isFalse();
  }

  @Test
  void queryTamperAfterSigningFails() {
    var signed = signer.sign(
        authority(),
        new TrustedGatewaySignerFixture.ClientRequestFacts(
            HttpMethod.GET.name(), PATH, "a=1", "", new byte[0]),
        new TrustedGatewaySignerFixture.SigningFreshness(NOW.getEpochSecond(), "nonce-query"));
    var tampered = new TrustedGatewaySignerFixture.SignedGatewayHeaders(
        signed.method(),
        signed.path(),
        "a=2",
        signed.bodyBytes(),
        signed.contentType(),
        signed.headers());
    assertThat(verifier().verify(request(tampered))).isFalse();
  }

  @Test
  void missingSignatureVersionIsRejected() {
    var signed = signed("nonce-missing-version")
        .withoutHeader(GatewayHeaderSignatureVerifier.VERSION_HEADER);
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
    String docs = Files.readString(BOUNDARY_DOC);
    assertThat(normalizeWhitespace(docs))
        .contains("Derive tenant, actor, and permissions from a trusted source")
        .contains("X-OrderPilot-Gateway-Signature");
    assertNoRealSecretMaterial(docs);
    assertNoRealSecretMaterial(Files.readString(SIGNER_FIXTURE));
  }

  @Test
  void crossLanguageFixtureCanonicalStringMatchesJavaVerifier() throws Exception {
    var fixture = new com.fasterxml.jackson.databind.ObjectMapper()
        .readTree(Files.readString(CROSS_LANGUAGE_FIXTURE));
    StringBuilder expectedCanonical = new StringBuilder();
    fixture.get("canonicalStringJoinedWithNewline").forEach(line -> {
      if (expectedCanonical.length() > 0) {
        expectedCanonical.append('\n');
      }
      expectedCanonical.append(line.asText());
    });

    String canonical = GatewayV2Canonical.build(
        fixture.get("method").asText(),
        fixture.get("path").asText(),
        fixture.get("rawQuery").asText(),
        fixture.get("contentType").asText(),
        fixture.get("bodySha256Hex").asText(),
        fixture.get("tenantId").asText(),
        fixture.get("actorId").asText(),
        fixture.get("permissionsHeaderValue").asText(),
        fixture.get("timestampEpochSeconds").asLong(),
        fixture.get("nonce").asText());

    assertThat(canonical).isEqualTo(expectedCanonical.toString());
    byte[] key = GatewayHmacKeyCodec.requireValid(
        "fixture", fixture.get("sharedSecretHexTestOnly").asText());
    assertThat(SignedActorVerifier.matchesHmacHex(
        key,
        canonical,
        fixture.get("expectedHmacSha256Hex").asText())).isTrue();
  }

  @Test
  void crossLanguageFixtureSignedHeadersAreAcceptedByBackendVerifier() throws Exception {
    var fixture = new com.fasterxml.jackson.databind.ObjectMapper()
        .readTree(Files.readString(CROSS_LANGUAGE_FIXTURE));
    long fixtureTimestamp = fixture.get("timestampEpochSeconds").asLong();
    Clock fixtureClock = Clock.fixed(Instant.ofEpochSecond(fixtureTimestamp), ZoneOffset.UTC);

    byte[] body = fixture.get("bodyUtf8").asText().getBytes(StandardCharsets.UTF_8);
    MockHttpServletRequest request = new MockHttpServletRequest(
        fixture.get("method").asText(), fixture.get("path").asText());
    request.setQueryString(fixture.get("rawQuery").asText());
    request.addHeader("Content-Type", fixture.get("contentType").asText());
    request.addHeader(GatewayHeaderSignatureVerifier.TENANT_HEADER, fixture.get("tenantId").asText());
    request.addHeader(RequestActorResolver.ACTOR_HEADER, fixture.get("actorId").asText());
    request.addHeader(ApiPermissionGuard.PERMISSIONS_HEADER, fixture.get("permissionsHeaderValue").asText());
    request.addHeader(GatewayHeaderSignatureVerifier.TIMESTAMP_HEADER, String.valueOf(fixtureTimestamp));
    request.addHeader(GatewayHeaderSignatureVerifier.NONCE_HEADER, fixture.get("nonce").asText());
    request.addHeader(GatewayHeaderSignatureVerifier.VERSION_HEADER, GatewayV2Canonical.SIGNATURE_VERSION);
    request.addHeader(
        GatewayHeaderSignatureVerifier.CONTENT_SHA256_HEADER, fixture.get("bodySha256Hex").asText());
    request.addHeader(GatewayHeaderSignatureVerifier.SIGNATURE_HEADER,
        fixture.get("expectedHmacSha256Hex").asText());

    CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request, body);

    GatewayHeaderSignatureVerifier verifier = new GatewayHeaderSignatureVerifier(
        fixture.get("sharedSecretHexTestOnly").asText(),
        MAX_SKEW_SECONDS,
        fixtureClock,
        new GatewayHeaderReplayGuard(fixtureClock, 100));

    assertThat(verifier.verify(cached)).isTrue();
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

  private static HttpServletRequest request(TrustedGatewaySignerFixture.SignedGatewayHeaders signed) {
    return TrustedGatewaySignerFixture.toCachedRequest(signed);
  }
}
