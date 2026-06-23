package com.orderpilot.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-43C — Production header-trust / gateway-auth hardening (enabled mode).
 *
 * <p>OP-CAP-43A/43B proved MVC route classification + default-deny and that no non-MVC surface
 * bypasses the model. This test pins the remaining boundary: when gateway-header authentication is
 * configured for production ({@code enabled=true}, {@code signature-required=true}, a shared secret
 * set), the authority headers ({@code X-Tenant-Id}, {@code X-OrderPilot-Actor-Id},
 * {@code X-OrderPilot-Permissions}) are trusted <b>only</b> when accompanied by a fresh, valid HMAC
 * gateway signature. A client cannot self-grant tenant/actor/permission authority by sending these
 * headers directly, and a valid signature authenticates context but never bypasses the permission
 * policy.
 *
 * <p>The existing {@link ApiHeaderAuthenticationFilterDisabledModeTest} covers the disabled mode, and
 * {@link ApiPermissionRouteCoverageTest} covers the dev/test fallback ({@code signature-required=false}).
 * This test is intentionally live MockMvc (not structural), and adds no production code.
 */
@WebMvcTest(controllers = ApiGatewayHeaderAuthenticationHardeningTest.GatewayProbeController.class)
@Import({
    CoreConfiguration.class,
    GlobalExceptionHandler.class,
    ApiSecurityWebConfig.class,
    ApiRouteSecurityPolicy.class,
    ApiPermissionInterceptor.class,
    ApiPermissionGuard.class,
    ApiGatewayHeaderAuthenticationHardeningTest.GatewayProbeController.class
})
@TestPropertySource(properties = {
    "orderpilot.security.gateway-header-auth.enabled=true",
    "orderpilot.security.gateway-header-auth.signature-required=true",
    "orderpilot.security.gateway-header-auth.shared-secret=" + ApiGatewayHeaderAuthenticationHardeningTest.SECRET,
    "orderpilot.security.gateway-header-auth.clock-skew-seconds=300"
})
class ApiGatewayHeaderAuthenticationHardeningTest {
  // Test-only static secret. NOT a real credential — only proves the HMAC contract.
  static final String SECRET = "op-cap-43c-test-only-gateway-shared-secret";

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "22222222-2222-2222-2222-222222222222";
  private static final String READ_ROUTE = "/api/stage8/analytics/stage43c-probe"; // ANALYTICS_READ
  private static final String MANAGE_ROUTE = "/api/stage8/reconciliation/refresh";  // ANALYTICS_MANAGE

  @Autowired private MockMvc mockMvc;

  // 1. Authority headers without any signature/timestamp must not authenticate — the chain treats the
  // request as anonymous and fails closed (401). The protected handler never runs.
  @Test
  void enabledGatewayHeaderAuthRejectsMissingSignatureOnProtectedRoute() throws Exception {
    MvcResult result = mockMvc.perform(get(READ_ROUTE)
            .header(GatewayHeaderSignatureVerifier.TENANT_HEADER, TENANT)
            .header(RequestActorResolver.ACTOR_HEADER, ACTOR)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, ApiPermission.ANALYTICS_READ.name()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
        .andReturn();
    assertNoSensitiveLeak(result);
  }

  // 2. A syntactically present but wrong signature must be rejected.
  @Test
  void enabledGatewayHeaderAuthRejectsInvalidSignature() throws Exception {
    long now = nowEpoch();
    MvcResult result = mockMvc.perform(get(READ_ROUTE)
            .header(GatewayHeaderSignatureVerifier.TENANT_HEADER, TENANT)
            .header(RequestActorResolver.ACTOR_HEADER, ACTOR)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, ApiPermission.ANALYTICS_READ.name())
            .header(GatewayHeaderSignatureVerifier.TIMESTAMP_HEADER, Long.toString(now))
            .header(GatewayHeaderSignatureVerifier.SIGNATURE_HEADER, "deadbeefdeadbeef"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
        .andReturn();
    assertNoSensitiveLeak(result);
  }

  // 3. A correctly-signed but stale timestamp (older than the skew window) must be rejected. The
  // signature is valid over the old timestamp; the freshness check still fails closed.
  @Test
  void enabledGatewayHeaderAuthRejectsExpiredTimestamp() throws Exception {
    long expired = nowEpoch() - 100_000;
    mockMvc.perform(signed(HttpMethod.GET, READ_ROUTE, TENANT, ACTOR, ApiPermission.ANALYTICS_READ.name(), expired))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  // 4. A correctly-signed but future timestamp beyond the skew window must be rejected.
  @Test
  void enabledGatewayHeaderAuthRejectsFutureTimestampBeyondSkew() throws Exception {
    long future = nowEpoch() + 100_000;
    mockMvc.perform(signed(HttpMethod.GET, READ_ROUTE, TENANT, ACTOR, ApiPermission.ANALYTICS_READ.name(), future))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  // 5. Valid signed gateway headers authenticate AND the permission matches → allowed. Proven for both
  // a read route and a write/mutation route, so the positive path is not a fluke of one classification.
  @Test
  void enabledGatewayHeaderAuthAcceptsValidSignedGatewayHeaders() throws Exception {
    mockMvc.perform(signed(HttpMethod.GET, READ_ROUTE, TENANT, ACTOR, ApiPermission.ANALYTICS_READ.name(), nowEpoch()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.route").value("stage8-read"));

    mockMvc.perform(signed(HttpMethod.POST, MANAGE_ROUTE, TENANT, ACTOR,
            ApiPermission.ANALYTICS_MANAGE.name(), nowEpoch())
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.route").value("stage8-refresh"));
  }

  // 6. A valid signature authenticates the context but does NOT bypass the permission policy. Signed
  // with a real-but-insufficient permission set → 403 TENANT_POLICY_DENIED from the interceptor.
  @Test
  void validSignatureWithoutRequiredPermissionStillDenied() throws Exception {
    MvcResult result = mockMvc.perform(
            signed(HttpMethod.GET, READ_ROUTE, TENANT, ACTOR, ApiPermission.INTAKE_READ.name(), nowEpoch()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_POLICY_DENIED"))
        .andExpect(jsonPath("$.message").value("Missing required API permission ANALYTICS_READ"))
        .andReturn();
    assertNoSensitiveLeak(result);
  }

  // 7. Permission escalation is impossible: the signature is bound to the permission value (it is part
  // of the canonical string). A client holding a properly-signed low grant cannot tamper the
  // X-OrderPilot-Permissions header up to ANALYTICS_MANAGE without invalidating the signature → 401,
  // and the mutation route never executes.
  @Test
  void clientCannotEscalatePermissionsByTamperingSignedHeader() throws Exception {
    long now = nowEpoch();
    // Signature computed over the low permission the gateway actually granted.
    String signatureOverLowGrant = SignedActorVerifier.hmacHex(SECRET,
        GatewayHeaderSignatureVerifier.canonical(
            new org.springframework.mock.web.MockHttpServletRequest(HttpMethod.POST.name(), MANAGE_ROUTE),
            TENANT, ACTOR, ApiPermission.ANALYTICS_READ.name(), now));

    MvcResult result = mockMvc.perform(post(MANAGE_ROUTE)
            .header(GatewayHeaderSignatureVerifier.TENANT_HEADER, TENANT)
            .header(RequestActorResolver.ACTOR_HEADER, ACTOR)
            // Tampered up to the manage permission the route requires...
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, ApiPermission.ANALYTICS_MANAGE.name())
            .header(GatewayHeaderSignatureVerifier.TIMESTAMP_HEADER, Long.toString(now))
            // ...but the signature only covers the low grant, so verification fails closed.
            .header(GatewayHeaderSignatureVerifier.SIGNATURE_HEADER, signatureOverLowGrant)
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
        .andReturn();
    assertThat(result.getResponse().getContentAsString())
        .as("escalation attempt must not reach the mutation handler")
        .doesNotContain("stage8-refresh");
    assertNoSensitiveLeak(result);
  }

  private MockHttpServletRequestBuilder signed(
      HttpMethod method, String path, String tenant, String actor, String permissions, long timestampEpoch) {
    String canonical = GatewayHeaderSignatureVerifier.canonical(
        new org.springframework.mock.web.MockHttpServletRequest(method.name(), path),
        tenant, actor, permissions, timestampEpoch);
    String signature = SignedActorVerifier.hmacHex(SECRET, canonical);
    MockHttpServletRequestBuilder builder = HttpMethod.POST.equals(method) ? post(path) : get(path);
    return builder
        .header(GatewayHeaderSignatureVerifier.TENANT_HEADER, tenant)
        .header(RequestActorResolver.ACTOR_HEADER, actor)
        .header(ApiPermissionGuard.PERMISSIONS_HEADER, permissions)
        .header(GatewayHeaderSignatureVerifier.TIMESTAMP_HEADER, Long.toString(timestampEpoch))
        .header(GatewayHeaderSignatureVerifier.SIGNATURE_HEADER, signature);
  }

  private static long nowEpoch() {
    return Instant.now().getEpochSecond();
  }

  // A denied response must never echo the tenant id, actor id, shared secret, presented signature, or
  // any canonical/HMAC internals back to the caller.
  private static void assertNoSensitiveLeak(MvcResult result) throws Exception {
    String body = result.getResponse().getContentAsString();
    assertThat(body)
        .doesNotContain(TENANT)
        .doesNotContain(ACTOR)
        .doesNotContain(SECRET)
        .doesNotContain("canonical")
        .doesNotContain("HmacSHA256")
        .doesNotContain("Expected");
  }

  @RestController
  static class GatewayProbeController {
    @GetMapping(READ_ROUTE)
    Map<String, String> read() {
      return Map.of("route", "stage8-read");
    }

    @PostMapping(MANAGE_ROUTE)
    Map<String, String> refresh() {
      return Map.of("route", "stage8-refresh");
    }
  }
}
