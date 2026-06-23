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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = ApiGatewayHeaderSignatureSecurityTest.SignedGatewayProbeController.class)
@Import({
    CoreConfiguration.class,
    GlobalExceptionHandler.class,
    ApiSecurityWebConfig.class,
    ApiRouteSecurityPolicy.class,
    ApiPermissionInterceptor.class,
    ApiPermissionGuard.class,
    ApiGatewayHeaderSignatureSecurityTest.SignedGatewayProbeController.class
})
@TestPropertySource(properties = {
    "orderpilot.security.gateway-header-auth.enabled=true",
    "orderpilot.security.gateway-header-auth.signature-required=true",
    "orderpilot.security.gateway-header-auth.shared-secret=test-gateway-shared-secret",
    "orderpilot.security.gateway-header-auth.clock-skew-seconds=300"
})
class ApiGatewayHeaderSignatureSecurityTest {
  private static final String SECRET = "test-gateway-shared-secret";
  private static final String TENANT = UUID.randomUUID().toString();
  private static final String ACTOR = UUID.randomUUID().toString();
  private static final String PATH = "/api/v1/operator-review/security-baseline/signed-gateway";

  @Autowired private MockMvc mockMvc;

  @Test
  void missingGatewaySignatureDoesNotAuthenticateAuthorityHeaders() throws Exception {
    mockMvc.perform(get(PATH)
            .header(GatewayHeaderSignatureVerifier.TENANT_HEADER, TENANT)
            .header(RequestActorResolver.ACTOR_HEADER, ACTOR)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, ApiPermission.REVIEW_READ.name()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void badGatewaySignatureDoesNotAuthenticateAuthorityHeaders() throws Exception {
    mockMvc.perform(unsignedGetWithSignature(PATH, ApiPermission.REVIEW_READ.name(),
            Instant.now().getEpochSecond(), "bad-signature"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void missingGatewayTimestampDoesNotAuthenticateAuthorityHeaders() throws Exception {
    mockMvc.perform(get(PATH)
            .header(GatewayHeaderSignatureVerifier.TENANT_HEADER, TENANT)
            .header(RequestActorResolver.ACTOR_HEADER, ACTOR)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, ApiPermission.REVIEW_READ.name())
            .header(GatewayHeaderSignatureVerifier.SIGNATURE_HEADER, "bad-signature"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void staleGatewayTimestampDoesNotAuthenticateAuthorityHeaders() throws Exception {
    mockMvc.perform(signedGet(PATH, ApiPermission.REVIEW_READ.name(), Instant.now().getEpochSecond() - 3600L))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void validGatewaySignatureWithRequiredPermissionAuthenticates() throws Exception {
    mockMvc.perform(signedGet(PATH, ApiPermission.REVIEW_READ.name(), Instant.now().getEpochSecond()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("protected"));
  }

  @Test
  void validGatewaySignatureWithInsufficientPermissionAuthenticatesThenFailsPermissionCheck() throws Exception {
    mockMvc.perform(signedGet(PATH, ApiPermission.ANALYTICS_READ.name(), Instant.now().getEpochSecond()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_POLICY_DENIED"))
        .andExpect(jsonPath("$.message").value("Missing required API permission REVIEW_READ"));
  }

  @Test
  void signedUnknownApiRouteRemainsDeniedByPermissionPolicy() throws Exception {
    String path = "/api/v1/stage40f-unclassified-probe";
    mockMvc.perform(signedGet(path, ApiPermission.REVIEW_READ.name(), Instant.now().getEpochSecond()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_POLICY_DENIED"))
        .andExpect(jsonPath("$.message").value("Unclassified API route GET " + path));
  }

  private static MockHttpServletRequestBuilder signedGet(String path, String permissions, long timestampEpoch) {
    // OP-CAP-43E: a unique nonce per signed request, bound into the canonical string.
    String nonce = UUID.randomUUID().toString();
    String canonical = "GET\n" + path + "\n" + TENANT + "\n" + ACTOR + "\n" + permissions + "\n"
        + timestampEpoch + "\n" + nonce;
    return unsignedGetWithSignature(path, permissions, timestampEpoch, nonce,
        SignedActorVerifier.hmacHex(SECRET, canonical));
  }

  private static MockHttpServletRequestBuilder unsignedGetWithSignature(
      String path,
      String permissions,
      long timestampEpoch,
      String signature) {
    return unsignedGetWithSignature(path, permissions, timestampEpoch, UUID.randomUUID().toString(), signature);
  }

  private static MockHttpServletRequestBuilder unsignedGetWithSignature(
      String path,
      String permissions,
      long timestampEpoch,
      String nonce,
      String signature) {
    return get(path)
        .header(GatewayHeaderSignatureVerifier.TENANT_HEADER, TENANT)
        .header(RequestActorResolver.ACTOR_HEADER, ACTOR)
        .header(ApiPermissionGuard.PERMISSIONS_HEADER, permissions)
        .header(GatewayHeaderSignatureVerifier.TIMESTAMP_HEADER, Long.toString(timestampEpoch))
        .header(GatewayHeaderSignatureVerifier.NONCE_HEADER, nonce)
        .header(GatewayHeaderSignatureVerifier.SIGNATURE_HEADER, signature);
  }

  @RestController
  static class SignedGatewayProbeController {
    @GetMapping("/api/v1/operator-review/security-baseline/signed-gateway")
    Map<String, String> read() {
      return Map.of("status", "protected");
    }

    @GetMapping("/api/v1/stage40f-unclassified-probe")
    Map<String, String> unclassified() {
      return Map.of("route", "unclassified");
    }
  }
}
