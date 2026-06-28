package com.orderpilot.security;

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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime security-chain proof for the Operant staff access plane.
 *
 * <p>This does not pretend to implement an IdP. It proves the existing signed-gateway boundary:
 * browser-supplied permission headers do not authenticate, tenant permissions cannot satisfy a
 * staff route, and STAFF_SUPPORT_READ is accepted only from a valid signed envelope.
 */
@WebMvcTest(controllers = InternalSupportGatewaySecurityTest.StaffProbeController.class)
@Import({
    CoreConfiguration.class,
    GlobalExceptionHandler.class,
    ApiSecurityWebConfig.class,
    ApiRouteSecurityPolicy.class,
    ApiPermissionInterceptor.class,
    ApiPermissionGuard.class,
    InternalSupportGatewaySecurityTest.StaffProbeController.class
})
@TestPropertySource(properties = {
    "orderpilot.security.gateway-header-auth.enabled=true",
    "orderpilot.security.gateway-header-auth.signature-required=true",
    "orderpilot.security.gateway-header-auth.shared-secret=" + InternalSupportGatewaySecurityTest.SECRET,
    "orderpilot.security.gateway-header-auth.clock-skew-seconds=300"
})
class InternalSupportGatewaySecurityTest {
  static final String SECRET = "wave-01-test-only-gateway-secret";
  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "22222222-2222-2222-2222-222222222222";
  private static final String ROUTE = "/api/v1/internal/support/runtime-proof";

  @Autowired private MockMvc mockMvc;

  @Test
  void forgedClientStaffPermissionHeaderWithoutGatewaySignatureIsRejected() throws Exception {
    mockMvc.perform(get(ROUTE)
            .header(GatewayHeaderSignatureVerifier.TENANT_HEADER, TENANT)
            .header(RequestActorResolver.ACTOR_HEADER, ACTOR)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, ApiPermission.STAFF_SUPPORT_READ.name()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void signedTenantAdminPermissionCannotAccessStaffRoute() throws Exception {
    mockMvc.perform(signed(ApiPermission.ADMIN_SETTINGS_MANAGE))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_POLICY_DENIED"))
        .andExpect(jsonPath("$.message").value("Missing required API permission STAFF_SUPPORT_READ"));
  }

  @Test
  void signedStaffPermissionCanPassTheStaffRouteEdge() throws Exception {
    mockMvc.perform(signed(ApiPermission.STAFF_SUPPORT_READ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.plane").value("OPERANT_STAFF"));
  }

  @Test
  void tamperingTenantPermissionIntoStaffPermissionInvalidatesSignature() throws Exception {
    long timestamp = Instant.now().getEpochSecond();
    String nonce = UUID.randomUUID().toString();
    MockHttpServletRequest canonicalRequest = new MockHttpServletRequest("GET", ROUTE);
    String signature = SignedActorVerifier.hmacHex(
        SECRET,
        GatewayHeaderSignatureVerifier.canonical(
            canonicalRequest,
            TENANT,
            ACTOR,
            ApiPermission.ADMIN_SETTINGS_MANAGE.name(),
            timestamp,
            nonce));

    mockMvc.perform(get(ROUTE)
            .header(GatewayHeaderSignatureVerifier.TENANT_HEADER, TENANT)
            .header(RequestActorResolver.ACTOR_HEADER, ACTOR)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, ApiPermission.STAFF_SUPPORT_READ.name())
            .header(GatewayHeaderSignatureVerifier.TIMESTAMP_HEADER, Long.toString(timestamp))
            .header(GatewayHeaderSignatureVerifier.NONCE_HEADER, nonce)
            .header(GatewayHeaderSignatureVerifier.SIGNATURE_HEADER, signature))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  private static MockHttpServletRequestBuilder signed(ApiPermission permission) {
    long timestamp = Instant.now().getEpochSecond();
    String nonce = UUID.randomUUID().toString();
    MockHttpServletRequest canonicalRequest = new MockHttpServletRequest("GET", ROUTE);
    String signature = SignedActorVerifier.hmacHex(
        SECRET,
        GatewayHeaderSignatureVerifier.canonical(
            canonicalRequest,
            TENANT,
            ACTOR,
            permission.name(),
            timestamp,
            nonce));
    return get(ROUTE)
        .header(GatewayHeaderSignatureVerifier.TENANT_HEADER, TENANT)
        .header(RequestActorResolver.ACTOR_HEADER, ACTOR)
        .header(ApiPermissionGuard.PERMISSIONS_HEADER, permission.name())
        .header(GatewayHeaderSignatureVerifier.TIMESTAMP_HEADER, Long.toString(timestamp))
        .header(GatewayHeaderSignatureVerifier.NONCE_HEADER, nonce)
        .header(GatewayHeaderSignatureVerifier.SIGNATURE_HEADER, signature);
  }

  @RestController
  static class StaffProbeController {
    @GetMapping(ROUTE)
    Map<String, String> staffProbe() {
      return Map.of("plane", "OPERANT_STAFF");
    }
  }
}
