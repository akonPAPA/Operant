package com.orderpilot.security;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.rest.InternalControlLifecycleController;
import com.orderpilot.application.services.control.lifecycle.LifecycleBackupOperationService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * P1-E2A - a signed EXECUTOR control credential (CONTROL_EXECUTOR_LEASE + CONTROL_EXECUTOR_REPORT) may
 * lease operations, but is DENIED on the staff request/read routes and never reaches the service on a
 * denied route. Proves the machine executor principal cannot request or read the staff surface.
 */
@WebMvcTest(controllers = InternalControlLifecycleController.class)
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
        + LifecycleControlExecutorCredentialSecurityTest.GATEWAY_SECRET,
    "orderpilot.security.control-plane-auth.credential-alias="
        + LifecycleControlExecutorCredentialSecurityTest.CREDENTIAL,
    "orderpilot.security.control-plane-auth.shared-secret="
        + LifecycleControlExecutorCredentialSecurityTest.CONTROL_SECRET,
    "orderpilot.security.control-plane-auth.audience=orderpilot-control-plane",
    "orderpilot.security.control-plane-auth.status=ENABLED",
    "orderpilot.security.control-plane-auth.valid-from=2026-01-01T00:00:00Z",
    "orderpilot.security.control-plane-auth.expires-at=2099-01-01T00:00:00Z",
    "orderpilot.security.control-plane-auth.permissions=CONTROL_EXECUTOR_LEASE,CONTROL_EXECUTOR_REPORT",
    "orderpilot.security.control-plane-auth.key-version=control-v1",
    "orderpilot.security.gateway-header-auth.clock-skew-seconds=300"
})
class LifecycleControlExecutorCredentialSecurityTest {
  static final String GATEWAY_SECRET =
      "a3f91c7e2b4d8056e1a9c0d4f7b26385e6a1d9c2b4f70835a6e9c1d2b3f40517";
  static final String CONTROL_SECRET =
      "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
  static final String CREDENTIAL = "ops-executor";
  private static final String BASE = "/api/v1/internal/control/lifecycle";

  @Autowired private MockMvc mockMvc;
  @MockBean private LifecycleBackupOperationService service;

  @Test
  void executorCredentialCanLease() throws Exception {
    when(service.leaseNext(anyString())).thenReturn(Optional.empty());

    mockMvc.perform(controlSigned("POST", BASE + "/executor/lease"))
        .andExpect(status().isNoContent());
  }

  @Test
  void executorCredentialIsDeniedOnBackupRequestRoute() throws Exception {
    mockMvc.perform(controlSigned("POST", BASE + "/backups").header("Idempotency-Key", "idem-1"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    verifyNoInteractions(service);
  }

  @Test
  void executorCredentialIsDeniedOnStaffReadRoute() throws Exception {
    mockMvc.perform(controlSigned("GET", BASE + "/operations/op_abc"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    verifyNoInteractions(service);
  }

  private static MockHttpServletRequestBuilder controlSigned(String method, String path) throws Exception {
    long now = Instant.now().getEpochSecond();
    String nonce = "nonce-" + UUID.randomUUID();
    String canonical = ControlPlaneProtocol.canonical(
        method,
        path,
        "",
        "",
        ControlPlaneProtocol.EMPTY_BODY_SHA256_HEX,
        ControlPlaneProtocol.AUDIENCE,
        CREDENTIAL,
        now,
        nonce);
    return MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path)
        .header(ControlPlaneProtocol.CREDENTIAL_HEADER, CREDENTIAL)
        .header(ControlPlaneProtocol.AUDIENCE_HEADER, ControlPlaneProtocol.AUDIENCE)
        .header(ControlPlaneProtocol.TIMESTAMP_HEADER, Long.toString(now))
        .header(ControlPlaneProtocol.NONCE_HEADER, nonce)
        .header(ControlPlaneProtocol.VERSION_HEADER, ControlPlaneProtocol.SIGNATURE_VERSION)
        .header(ControlPlaneProtocol.CONTENT_SHA256_HEADER, ControlPlaneProtocol.EMPTY_BODY_SHA256_HEX)
        .header(ControlPlaneProtocol.SIGNATURE_HEADER, hmac(CONTROL_SECRET, canonical));
  }

  private static String hmac(String secretHex, String canonical) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(HexFormat.of().parseHex(secretHex), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
  }
}
