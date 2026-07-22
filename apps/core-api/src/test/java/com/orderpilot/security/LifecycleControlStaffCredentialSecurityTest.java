package com.orderpilot.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.rest.InternalControlLifecycleController;
import com.orderpilot.application.services.control.lifecycle.LifecycleBackupOperationService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.domain.control.LifecycleOperation;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
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

/** Staff credential route and denied-no-service proofs for lifecycle control. */
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
        + LifecycleControlStaffCredentialSecurityTest.GATEWAY_SECRET,
    "orderpilot.security.control-plane-auth.principal-id=staff:operations",
    "orderpilot.security.control-plane-auth.credential-alias="
        + LifecycleControlStaffCredentialSecurityTest.CREDENTIAL,
    "orderpilot.security.control-plane-auth.shared-secret="
        + LifecycleControlStaffCredentialSecurityTest.CONTROL_SECRET,
    "orderpilot.security.control-plane-auth.audience=orderpilot-control-plane",
    "orderpilot.security.control-plane-auth.status=ENABLED",
    "orderpilot.security.control-plane-auth.valid-from=2026-01-01T00:00:00Z",
    "orderpilot.security.control-plane-auth.expires-at=2099-01-01T00:00:00Z",
    "orderpilot.security.control-plane-auth.permissions=STAFF_CONTROL_BACKUP,STAFF_CONTROL_LIFECYCLE_READ",
    "orderpilot.security.control-plane-auth.key-version=control-v1",
    "orderpilot.security.gateway-header-auth.clock-skew-seconds=300"
})
class LifecycleControlStaffCredentialSecurityTest {
  static final String GATEWAY_SECRET =
      "a3f91c7e2b4d8056e1a9c0d4f7b26385e6a1d9c2b4f70835a6e9c1d2b3f40517";
  static final String CONTROL_SECRET =
      "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
  static final String CREDENTIAL = "ops-prod";
  private static final String BASE = "/api/v1/internal/control/lifecycle";

  @Autowired private MockMvc mockMvc;
  @MockBean private LifecycleBackupOperationService service;

  @Test
  void staffCredentialCanRequestBackupWithSignedIdempotencyIntent() throws Exception {
    when(service.requestBackup(anyString(), any()))
        .thenReturn(LifecycleOperation.queuedBackup("op_abc", "hash", "fp", Instant.EPOCH));

    String body = "{\"idempotencyKey\":\"staff-backup-attempt-001\"}";
    mockMvc.perform(controlSigned("POST", BASE + "/backups", body))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.operationId").value("op_abc"))
        .andExpect(jsonPath("$.state").value("QUEUED"));
  }

  @Test
  void tamperingSignedBackupBodyInvalidatesAuthenticationAndNeverInvokesService() throws Exception {
    String signedBody = "{\"idempotencyKey\":\"staff-backup-attempt-001\"}";
    MockHttpServletRequestBuilder request = controlSigned("POST", BASE + "/backups", signedBody)
        .content("{\"idempotencyKey\":\"staff-backup-attempt-002\"}");

    mockMvc.perform(request).andExpect(status().isUnauthorized());
    verifyNoInteractions(service);
  }

  @Test
  void staffCredentialIsDeniedOnExecutorLeaseRoute() throws Exception {
    mockMvc.perform(controlSigned("POST", BASE + "/executor/lease", null))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    verifyNoInteractions(service);
  }

  @Test
  void staffCredentialIsDeniedOnExecutorCompleteRoute() throws Exception {
    mockMvc.perform(controlSigned("POST", BASE + "/operations/op_abc/complete", null))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    verifyNoInteractions(service);
  }

  @Test
  void unauthenticatedRequestIsDeniedAndNeverReachesService() throws Exception {
    mockMvc.perform(MockMvcRequestBuilders.post(BASE + "/backups")
            .contentType("application/json")
            .content("{\"idempotencyKey\":\"unauthenticated-attempt-001\"}"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(service);
  }

  private static MockHttpServletRequestBuilder controlSigned(
      String method, String path, String body) throws Exception {
    byte[] bodyBytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    String contentType = body == null ? "" : "application/json";
    String bodySha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bodyBytes));
    long now = Instant.now().getEpochSecond();
    String nonce = "nonce-" + UUID.randomUUID();
    String canonical = ControlPlaneProtocol.canonical(
        method,
        path,
        "",
        contentType,
        bodySha,
        ControlPlaneProtocol.AUDIENCE,
        CREDENTIAL,
        now,
        nonce);
    MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path)
        .header(ControlPlaneProtocol.CREDENTIAL_HEADER, CREDENTIAL)
        .header(ControlPlaneProtocol.AUDIENCE_HEADER, ControlPlaneProtocol.AUDIENCE)
        .header(ControlPlaneProtocol.TIMESTAMP_HEADER, Long.toString(now))
        .header(ControlPlaneProtocol.NONCE_HEADER, nonce)
        .header(ControlPlaneProtocol.VERSION_HEADER, ControlPlaneProtocol.SIGNATURE_VERSION)
        .header(ControlPlaneProtocol.CONTENT_SHA256_HEADER, bodySha)
        .header(ControlPlaneProtocol.SIGNATURE_HEADER, hmac(CONTROL_SECRET, canonical));
    if (body != null) {
      request.contentType(contentType).content(body);
    }
    return request;
  }

  private static String hmac(String secretHex, String canonical) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(HexFormat.of().parseHex(secretHex), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
  }
}
