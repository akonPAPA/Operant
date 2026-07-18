package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.ControlInternalDtos.ControlHealthResponse;
import com.orderpilot.api.rest.InternalControlController;
import com.orderpilot.application.services.control.ControlPlaneStatusService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * P1-E plane-separation proof for control-plane authentication. The control credential protocol is
 * separate from the browser gateway signature path: it carries no tenant, actor, or permission
 * headers, Core resolves the control principal and route permission after verifying the control
 * credential, and the retired gateway key selector cannot mint control authority.
 */
@WebMvcTest(controllers = InternalControlController.class)
@Import({
    CoreConfiguration.class,
    GlobalExceptionHandler.class,
    ApiSecurityWebConfig.class,
    ApiRouteSecurityPolicy.class,
    ApiPermissionInterceptor.class,
    ApiPermissionGuard.class
})
@TestPropertySource(properties = {
    "orderpilot.security.gateway-header-auth.enabled=true",
    "orderpilot.security.gateway-header-auth.signature-required=true",
    "orderpilot.security.gateway-header-auth.shared-secret="
        + ControlPlaneKeySeparationSecurityTest.GATEWAY_SECRET,
    "orderpilot.security.control-plane-auth.credential-alias="
        + ControlPlaneKeySeparationSecurityTest.CREDENTIAL,
    "orderpilot.security.control-plane-auth.shared-secret="
        + ControlPlaneKeySeparationSecurityTest.CONTROL_SECRET,
    "orderpilot.security.control-plane-auth.audience=orderpilot-control-plane",
    "orderpilot.security.control-plane-auth.status=ENABLED",
    "orderpilot.security.control-plane-auth.valid-from=2026-01-01T00:00:00Z",
    "orderpilot.security.control-plane-auth.expires-at=2099-01-01T00:00:00Z",
    "orderpilot.security.control-plane-auth.permissions=STAFF_CONTROL_READ,STAFF_CONTROL_DIAGNOSE",
    "orderpilot.security.control-plane-auth.key-version=control-v1",
    "orderpilot.security.gateway-header-auth.clock-skew-seconds=300"
})
class ControlPlaneKeySeparationSecurityTest {
  static final String GATEWAY_SECRET =
      "a3f91c7e2b4d8056e1a9c0d4f7b26385e6a1d9c2b4f70835a6e9c1d2b3f40517";
  static final String CONTROL_SECRET =
      "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
  static final String CREDENTIAL = "ops-prod";
  private static final String TENANT = UUID.randomUUID().toString();
  private static final String ACTOR = UUID.randomUUID().toString();
  private static final String HEALTH = "/api/v1/internal/control/health";
  private static final String READINESS = "/api/v1/internal/control/readiness";
  private static final String QUOTES = "/api/v1/quotes";

  @Autowired private MockMvc mockMvc;

  @MockBean private ControlPlaneStatusService statusService;

  @Test
  void controlCredentialAuthenticatesAsAttributedControlPrincipalAndCoreResolvesPermission() throws Exception {
    AtomicReference<Object> principal = new AtomicReference<>();
    when(statusService.health()).thenAnswer(invocation -> {
      principal.set(SecurityContextHolder.getContext().getAuthentication().getPrincipal());
      return new ControlHealthResponse("UP");
    });

    mockMvc.perform(controlSigned(HEALTH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));

    assertThat(principal.get()).isInstanceOf(ControlPlanePrincipal.class);
    ControlPlanePrincipal controlPrincipal = (ControlPlanePrincipal) principal.get();
    assertThat(controlPrincipal.credentialAlias()).isEqualTo(CREDENTIAL);
    assertThat(controlPrincipal.keyVersion()).isEqualTo("control-v1");
    assertThat(controlPrincipal.principalType()).isEqualTo(ControlPlaneCredentialRegistry.PRINCIPAL_TYPE);
    assertThat(controlPrincipal.toString())
        .doesNotContain(CONTROL_SECRET)
        .doesNotContain(GATEWAY_SECRET)
        .doesNotContain("001122")
        .doesNotContain("a3f91");
  }

  @Test
  void differentServerCredentialRecordsProduceDistinguishablePrincipals() {
    Clock clock = Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC);
    var first = new ControlPlaneCredentialRegistry(
        "ops-prod-a", CONTROL_SECRET, ControlPlaneProtocol.AUDIENCE, "ENABLED",
        "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", false,
        "STAFF_CONTROL_READ", "control-v1", clock)
        .findActive("ops-prod-a", ControlPlaneProtocol.AUDIENCE)
        .orElseThrow()
        .principal();
    var second = new ControlPlaneCredentialRegistry(
        "ops-prod-b", "11112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
        ControlPlaneProtocol.AUDIENCE, "ENABLED", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", false,
        "STAFF_CONTROL_READ", "control-v2", clock)
        .findActive("ops-prod-b", ControlPlaneProtocol.AUDIENCE)
        .orElseThrow()
        .principal();

    assertThat(first).isNotEqualTo(second);
    assertThat(first.credentialAlias()).isEqualTo("ops-prod-a");
    assertThat(second.credentialAlias()).isEqualTo("ops-prod-b");
    assertThat(second.keyVersion()).isEqualTo("control-v2");
  }
  @Test
  void controlCredentialCannotAuthenticateTenantBusinessRoutes() throws Exception {
    mockMvc.perform(controlSigned(QUOTES))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    verifyNoInteractions(statusService);
  }

  @Test
  void gatewayKeyCanNeverMintControlPermission() throws Exception {
    mockMvc.perform(TrustedGatewayTestSigning.signedGet(
            GATEWAY_SECRET, HEALTH, TENANT, ACTOR, "STAFF_CONTROL_READ", Instant.now().getEpochSecond()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    verifyNoInteractions(statusService);
  }

  @Test
  void retiredGatewayControlKeySelectorFailsClosed() throws Exception {
    mockMvc.perform(TrustedGatewayTestSigning.signedGet(
            CONTROL_SECRET, HEALTH, TENANT, ACTOR, "STAFF_CONTROL_READ", Instant.now().getEpochSecond())
            .header(GatewayHeaderSignatureVerifier.KEY_ID_HEADER, "control"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    verifyNoInteractions(statusService);
  }

  @Test
  void retiredGatewayControlKeySelectorFailsClosedAtVerifierLevel() {
    GatewayHeaderSignatureVerifier verifier = new GatewayHeaderSignatureVerifier(
        GATEWAY_SECRET, 300, Clock.systemUTC(), new GatewayHeaderReplayGuard(Clock.systemUTC(), 100));
    MockHttpServletRequest request = new MockHttpServletRequest("GET", HEALTH);
    request.addHeader(GatewayHeaderSignatureVerifier.KEY_ID_HEADER, "control");
    assertThat(verifier.verify(request)).isFalse();
  }

  @Test
  void registryOwnsCredentialStatusExpiryRevocationAudienceAndPermissions() {
    Clock clock = Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC);
    assertThat(registry(CREDENTIAL, "ENABLED", false, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
            "STAFF_CONTROL_READ").findActive(CREDENTIAL, ControlPlaneProtocol.AUDIENCE))
        .isPresent();
    assertThat(registry(CREDENTIAL, "DISABLED", false, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
            "STAFF_CONTROL_READ").findActive(CREDENTIAL, ControlPlaneProtocol.AUDIENCE))
        .isEmpty();
    assertThat(registry(CREDENTIAL, "ENABLED", true, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
            "STAFF_CONTROL_READ").findActive(CREDENTIAL, ControlPlaneProtocol.AUDIENCE))
        .isEmpty();
    assertThat(registry(CREDENTIAL, "ENABLED", false, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z",
            "STAFF_CONTROL_READ").findActive(CREDENTIAL, ControlPlaneProtocol.AUDIENCE))
        .isEmpty();
    assertThat(new ControlPlaneCredentialRegistry(
            CREDENTIAL, CONTROL_SECRET, "other-audience", "ENABLED", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", false,
            "STAFF_CONTROL_READ", "1", clock).findActive(CREDENTIAL, ControlPlaneProtocol.AUDIENCE))
        .isEmpty();
  }

  @Test
  void registryRejectsWhitespacePaddedEnabledAuthorityProperties() {
    Clock clock = Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC);
    assertThat(new ControlPlaneCredentialRegistry(
            " ops-prod", CONTROL_SECRET, ControlPlaneProtocol.AUDIENCE, "ENABLED",
            "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", false,
            "STAFF_CONTROL_READ", "1", clock).findActive(CREDENTIAL, ControlPlaneProtocol.AUDIENCE))
        .isEmpty();
    assertThat(new ControlPlaneCredentialRegistry(
            CREDENTIAL, CONTROL_SECRET, ControlPlaneProtocol.AUDIENCE, "ENABLED",
            " 2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", false,
            "STAFF_CONTROL_READ", "1", clock).findActive(CREDENTIAL, ControlPlaneProtocol.AUDIENCE))
        .isEmpty();
    assertThat(new ControlPlaneCredentialRegistry(
            CREDENTIAL, CONTROL_SECRET, ControlPlaneProtocol.AUDIENCE, "ENABLED",
            "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", false,
            " STAFF_CONTROL_READ", "1", clock).findActive(CREDENTIAL, ControlPlaneProtocol.AUDIENCE))
        .isEmpty();
  }

  @Test
  void unsupportedVersionMethodPathQueryBodyContentTypeAudienceAliasAndTimestampTamperingAreDenied() throws Exception {
    long now = Instant.now().getEpochSecond();
    String noncePrefix = "tamper-" + UUID.randomUUID();
    mockMvc.perform(controlSigned(HEALTH, HttpMethod.GET, HEALTH, "", now, noncePrefix + "-version",
            ControlPlaneProtocol.AUDIENCE, CREDENTIAL, "2", ControlPlaneProtocol.EMPTY_BODY_SHA256_HEX, null))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(controlSigned(HEALTH, HttpMethod.HEAD, HEALTH, "", now, noncePrefix + "-method",
            ControlPlaneProtocol.AUDIENCE, CREDENTIAL, ControlPlaneProtocol.SIGNATURE_VERSION,
            ControlPlaneProtocol.EMPTY_BODY_SHA256_HEX, null, "GET"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(controlSigned(READINESS, HttpMethod.GET, HEALTH, "", now, noncePrefix + "-path",
            ControlPlaneProtocol.AUDIENCE, CREDENTIAL, ControlPlaneProtocol.SIGNATURE_VERSION,
            ControlPlaneProtocol.EMPTY_BODY_SHA256_HEX, null))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(controlSigned(HEALTH + "?probe=true", HttpMethod.GET, HEALTH, "", now, noncePrefix + "-query",
            ControlPlaneProtocol.AUDIENCE, CREDENTIAL, ControlPlaneProtocol.SIGNATURE_VERSION,
            ControlPlaneProtocol.EMPTY_BODY_SHA256_HEX, null))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(controlSigned(HEALTH, HttpMethod.GET, HEALTH, "", now, noncePrefix + "-body",
            ControlPlaneProtocol.AUDIENCE, CREDENTIAL, ControlPlaneProtocol.SIGNATURE_VERSION,
            ControlPlaneProtocol.EMPTY_BODY_SHA256_HEX, "tampered".getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(controlSigned(HEALTH, HttpMethod.GET, HEALTH, "", now, noncePrefix + "-audience",
            "other-audience", CREDENTIAL, ControlPlaneProtocol.SIGNATURE_VERSION,
            ControlPlaneProtocol.EMPTY_BODY_SHA256_HEX, null))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(controlSigned(HEALTH, HttpMethod.GET, HEALTH, "", now, noncePrefix + "-alias",
            ControlPlaneProtocol.AUDIENCE, "other-alias", ControlPlaneProtocol.SIGNATURE_VERSION,
            ControlPlaneProtocol.EMPTY_BODY_SHA256_HEX, null))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(controlSigned(HEALTH, HttpMethod.GET, HEALTH, "", now - 3_600, noncePrefix + "-timestamp",
            ControlPlaneProtocol.AUDIENCE, CREDENTIAL, ControlPlaneProtocol.SIGNATURE_VERSION,
            ControlPlaneProtocol.EMPTY_BODY_SHA256_HEX, null))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(statusService);
  }

  @Test
  void replayedNonceIsDeniedAfterFirstUse() throws Exception {
    when(statusService.health()).thenReturn(new ControlHealthResponse("UP"));
    long now = Instant.now().getEpochSecond();
    String nonce = "replay-" + UUID.randomUUID();
    mockMvc.perform(controlSigned(HEALTH, HttpMethod.GET, HEALTH, "", now, nonce,
            ControlPlaneProtocol.AUDIENCE, CREDENTIAL, ControlPlaneProtocol.SIGNATURE_VERSION,
            ControlPlaneProtocol.EMPTY_BODY_SHA256_HEX, null))
        .andExpect(status().isOk());
    mockMvc.perform(controlSigned(HEALTH, HttpMethod.GET, HEALTH, "", now, nonce,
            ControlPlaneProtocol.AUDIENCE, CREDENTIAL, ControlPlaneProtocol.SIGNATURE_VERSION,
            ControlPlaneProtocol.EMPTY_BODY_SHA256_HEX, null))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void concurrentDuplicateNonceAdmissionAllowsExactlyOneCaller() throws Exception {
    GatewayHeaderReplayGuard guard = new GatewayHeaderReplayGuard(Clock.systemUTC(), 100);
    var executor = Executors.newFixedThreadPool(12);
    CountDownLatch start = new CountDownLatch(1);
    try {
      var futures = java.util.stream.IntStream.range(0, 12)
          .mapToObj(index -> executor.submit(() -> {
            start.await();
            return guard.admitFirstUse(
                "control-plane:" + ControlPlaneProtocol.AUDIENCE,
                "credential:" + CREDENTIAL,
                "duplicate-nonce",
                Duration.ofSeconds(600));
          }))
          .toList();
      start.countDown();
      int admitted = 0;
      for (var future : futures) {
        if (future.get()) {
          admitted++;
        }
      }
      assertThat(admitted).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  private static ControlPlaneCredentialRegistry registry(
      String alias, String status, boolean revoked, String validFrom, String expiresAt, String permissions) {
    return new ControlPlaneCredentialRegistry(
        alias,
        CONTROL_SECRET,
        ControlPlaneProtocol.AUDIENCE,
        status,
        validFrom,
        expiresAt,
        revoked,
        permissions,
        "1",
        Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC));
  }

  private static MockHttpServletRequestBuilder controlSigned(String path) throws Exception {
    long now = Instant.now().getEpochSecond();
    return controlSigned(path, HttpMethod.GET, path, "", now, "nonce-" + UUID.randomUUID(),
        ControlPlaneProtocol.AUDIENCE, CREDENTIAL, ControlPlaneProtocol.SIGNATURE_VERSION,
        ControlPlaneProtocol.EMPTY_BODY_SHA256_HEX, null);
  }

  private static MockHttpServletRequestBuilder controlSigned(
      String requestPath,
      HttpMethod requestMethod,
      String signedPath,
      String signedRawQuery,
      long timestampEpoch,
      String nonce,
      String audience,
      String credential,
      String version,
      String contentShaHeader,
      byte[] body) throws Exception {
    return controlSigned(requestPath, requestMethod, signedPath, signedRawQuery, timestampEpoch, nonce, audience,
        credential, version, contentShaHeader, body, requestMethod.name());
  }

  private static MockHttpServletRequestBuilder controlSigned(
      String requestPath,
      HttpMethod requestMethod,
      String signedPath,
      String signedRawQuery,
      long timestampEpoch,
      String nonce,
      String audience,
      String credential,
      String version,
      String contentShaHeader,
      byte[] body,
      String signedMethod) throws Exception {
    String canonical = ControlPlaneProtocol.canonical(
        signedMethod,
        signedPath,
        signedRawQuery,
        "",
        ControlPlaneProtocol.EMPTY_BODY_SHA256_HEX,
        audience,
        credential,
        timestampEpoch,
        nonce);
    MockHttpServletRequestBuilder builder = request(requestMethod, requestPath)
        .header(ControlPlaneProtocol.CREDENTIAL_HEADER, credential)
        .header(ControlPlaneProtocol.AUDIENCE_HEADER, audience)
        .header(ControlPlaneProtocol.TIMESTAMP_HEADER, Long.toString(timestampEpoch))
        .header(ControlPlaneProtocol.NONCE_HEADER, nonce)
        .header(ControlPlaneProtocol.VERSION_HEADER, version)
        .header(ControlPlaneProtocol.CONTENT_SHA256_HEADER, contentShaHeader)
        .header(ControlPlaneProtocol.SIGNATURE_HEADER, hmac(CONTROL_SECRET, canonical));
    if (body != null) {
      builder.content(body);
    }
    return builder;
  }

  private static String hmac(String secretHex, String canonical) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(HexFormat.of().parseHex(secretHex), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
  }
}
