package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-43E — Gateway header replay protection (nonce/jti single-use admission).
 *
 * <p>OP-CAP-43C proved the HMAC verifier rejects forged/expired/tampered signatures, and that a valid
 * signature never bypasses the permission policy. The residual risk it left open: a captured,
 * still-fresh signed request could be <b>replayed</b> within the {@code clock-skew-seconds} window and
 * reuse its tenant/actor/permission authority. This test pins the fix — the gateway must include a
 * unique {@code X-OrderPilot-Gateway-Nonce} bound into the HMAC canonical string, and the backend
 * admits each nonce at most once.
 *
 * <p>Live MockMvc against the same security chain as 43C; the per-class context shares one
 * {@code GatewayHeaderReplayGuard}, so a second identical request is a true replay. Disabled-mode and
 * dev/test unsigned-mode behavior are covered by {@link ApiHeaderAuthenticationFilterDisabledModeTest}
 * and {@link ApiPermissionRouteCoverageTest}; the production startup guard by
 * {@link GatewayHeaderAuthProductionGuardTest}.
 */
@WebMvcTest(controllers = GatewayHeaderReplayProtectionTest.ReplayProbeController.class)
@Import({
    CoreConfiguration.class,
    GlobalExceptionHandler.class,
    ApiSecurityWebConfig.class,
    ApiRouteSecurityPolicy.class,
    ApiPermissionInterceptor.class,
    ApiPermissionGuard.class,
    GatewayHeaderReplayProtectionTest.ReplayProbeController.class
})
@TestPropertySource(properties = {
    "orderpilot.security.gateway-header-auth.enabled=true",
    "orderpilot.security.gateway-header-auth.signature-required=true",
    "orderpilot.security.gateway-header-auth.shared-secret=" + GatewayHeaderReplayProtectionTest.SECRET,
    "orderpilot.security.gateway-header-auth.clock-skew-seconds=300"
})
class GatewayHeaderReplayProtectionTest {
  // Test-only static secret. NOT a real credential — only proves the HMAC + replay contract.
  static final String SECRET = "op-cap-43e-test-only-gateway-shared-secret";

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "22222222-2222-2222-2222-222222222222";
  private static final String READ_ROUTE = "/api/stage8/analytics/stage43e-probe"; // ANALYTICS_READ

  @Autowired private MockMvc mockMvc;

  // 1. A valid signature with NO nonce header must be rejected when signature-required=true.
  @Test
  void signedGatewayHeadersRequireNonceWhenSignatureRequired() throws Exception {
    long now = nowEpoch();
    String nonce = freshNonce();
    String signature = signature(READ_ROUTE, ApiPermission.ANALYTICS_READ.name(), now, nonce);
    // Signature is valid over the canonical (incl. nonce) but the nonce header is omitted → 401.
    mockMvc.perform(get(READ_ROUTE)
            .header(GatewayHeaderSignatureVerifier.TENANT_HEADER, TENANT)
            .header(RequestActorResolver.ACTOR_HEADER, ACTOR)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, ApiPermission.ANALYTICS_READ.name())
            .header(GatewayHeaderSignatureVerifier.TIMESTAMP_HEADER, Long.toString(now))
            .header(GatewayHeaderSignatureVerifier.SIGNATURE_HEADER, signature))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  // 2. A valid signature with a fresh nonce and the required permission is accepted.
  @Test
  void validSignedGatewayHeadersWithFreshNonceAreAccepted() throws Exception {
    mockMvc.perform(signed(READ_ROUTE, ApiPermission.ANALYTICS_READ.name(), nowEpoch(), freshNonce()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.route").value("stage8-replay-probe"));
  }

  // 3. Replaying the exact same signed request (same nonce) is rejected: first 200, second 401.
  @Test
  void replayedSignedGatewayHeadersWithSameNonceAreRejected() throws Exception {
    long now = nowEpoch();
    String nonce = freshNonce();

    mockMvc.perform(signed(READ_ROUTE, ApiPermission.ANALYTICS_READ.name(), now, nonce))
        .andExpect(status().isOk());

    MvcResult replay = mockMvc.perform(signed(READ_ROUTE, ApiPermission.ANALYTICS_READ.name(), now, nonce))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
        .andReturn();
    assertNoSensitiveLeak(replay, nonce);
  }

  // 4. A signature bound to nonce A but sent with nonce B does not verify → 401, and nonce B is never
  //    admitted (a tampered request cannot burn a legitimate nonce slot).
  @Test
  void tamperedNonceWithoutResigningIsRejected() throws Exception {
    long now = nowEpoch();
    String nonceA = freshNonce();
    String nonceB = freshNonce();
    String signatureOverA = signature(READ_ROUTE, ApiPermission.ANALYTICS_READ.name(), now, nonceA);

    mockMvc.perform(get(READ_ROUTE)
            .header(GatewayHeaderSignatureVerifier.TENANT_HEADER, TENANT)
            .header(RequestActorResolver.ACTOR_HEADER, ACTOR)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, ApiPermission.ANALYTICS_READ.name())
            .header(GatewayHeaderSignatureVerifier.TIMESTAMP_HEADER, Long.toString(now))
            .header(GatewayHeaderSignatureVerifier.NONCE_HEADER, nonceB)
            .header(GatewayHeaderSignatureVerifier.SIGNATURE_HEADER, signatureOverA))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

    // Because nonce B was never admitted, a correctly-signed first use of nonce B still succeeds.
    mockMvc.perform(signed(READ_ROUTE, ApiPermission.ANALYTICS_READ.name(), nowEpoch(), nonceB))
        .andExpect(status().isOk());
  }

  // 5. A correctly-signed but expired timestamp is rejected by the freshness check (before/along with
  //    replay admission), so an expired nonce can never be admitted.
  @Test
  void sameNonceAfterTimestampExpiryIsRejectedOrExpiredSafely() throws Exception {
    long expired = nowEpoch() - 100_000;
    mockMvc.perform(signed(READ_ROUTE, ApiPermission.ANALYTICS_READ.name(), expired, freshNonce()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  // 6. A valid signed nonce authenticates context but does NOT bypass the permission policy.
  @Test
  void validSignatureWithoutRequiredPermissionStillDenied() throws Exception {
    MvcResult result = mockMvc.perform(
            signed(READ_ROUTE, ApiPermission.INTAKE_READ.name(), nowEpoch(), freshNonce()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_POLICY_DENIED"))
        .andExpect(jsonPath("$.message").value("Missing required API permission ANALYTICS_READ"))
        .andReturn();
    assertNoSensitiveLeak(result, null);
  }

  // 7. A replay rejection must not echo nonce-store internals, the nonce, signature, canonical string,
  //    secret, tenant, or actor.
  @Test
  void replayRejectionDoesNotLeakSensitiveMaterial() throws Exception {
    long now = nowEpoch();
    String nonce = freshNonce();
    mockMvc.perform(signed(READ_ROUTE, ApiPermission.ANALYTICS_READ.name(), now, nonce))
        .andExpect(status().isOk());
    MvcResult replay = mockMvc.perform(signed(READ_ROUTE, ApiPermission.ANALYTICS_READ.name(), now, nonce))
        .andExpect(status().isUnauthorized())
        .andReturn();
    assertNoSensitiveLeak(replay, nonce);
  }

  private MockHttpServletRequestBuilder signed(String path, String permissions, long timestampEpoch, String nonce) {
    return get(path)
        .header(GatewayHeaderSignatureVerifier.TENANT_HEADER, TENANT)
        .header(RequestActorResolver.ACTOR_HEADER, ACTOR)
        .header(ApiPermissionGuard.PERMISSIONS_HEADER, permissions)
        .header(GatewayHeaderSignatureVerifier.TIMESTAMP_HEADER, Long.toString(timestampEpoch))
        .header(GatewayHeaderSignatureVerifier.NONCE_HEADER, nonce)
        .header(GatewayHeaderSignatureVerifier.SIGNATURE_HEADER, signature(path, permissions, timestampEpoch, nonce));
  }

  private static String signature(String path, String permissions, long timestampEpoch, String nonce) {
    String canonical = GatewayHeaderSignatureVerifier.canonical(
        new org.springframework.mock.web.MockHttpServletRequest("GET", path),
        TENANT, ACTOR, permissions, timestampEpoch, nonce);
    return SignedActorVerifier.hmacHex(SECRET, canonical);
  }

  private static String freshNonce() {
    return UUID.randomUUID().toString();
  }

  private static long nowEpoch() {
    return Instant.now().getEpochSecond();
  }

  private static void assertNoSensitiveLeak(MvcResult result, String nonce) throws Exception {
    String body = result.getResponse().getContentAsString();
    assertThat(body)
        .doesNotContain(TENANT)
        .doesNotContain(ACTOR)
        .doesNotContain(SECRET)
        .doesNotContain("canonical")
        .doesNotContain("HmacSHA256")
        .doesNotContain("replay")
        .doesNotContain("nonce");
    if (nonce != null) {
      assertThat(body).doesNotContain(nonce);
    }
  }

  @RestController
  static class ReplayProbeController {
    @GetMapping(READ_ROUTE)
    Map<String, String> read() {
      return Map.of("route", "stage8-replay-probe");
    }
  }
}
