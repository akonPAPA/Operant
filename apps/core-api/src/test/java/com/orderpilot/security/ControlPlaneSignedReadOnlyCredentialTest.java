package com.orderpilot.security;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.ControlInternalDtos.ControlStatusResponse;
import com.orderpilot.api.dto.ControlInternalDtos.DependencyState;
import com.orderpilot.api.dto.ControlInternalDtos.DependencyStatus;
import com.orderpilot.api.rest.InternalControlController;
import com.orderpilot.application.services.control.ControlPlaneStatusService;
import com.orderpilot.application.services.control.OperationalEventAccessAuditor;
import com.orderpilot.application.services.control.OperationalEventReadService;
import com.orderpilot.application.services.control.OperationalEventRecorder;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
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

/**
 * Proof that a STAFF_CONTROL_READ-only signed credential cannot cross into diagnostics or the
 * dedicated operational-event permission and is denied before service/recorder/audit effects.
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
        + ControlPlaneSignedReadOnlyCredentialTest.GATEWAY_SECRET,
    "orderpilot.security.control-plane-auth.credential-alias="
        + ControlPlaneSignedReadOnlyCredentialTest.CREDENTIAL,
    "orderpilot.security.control-plane-auth.shared-secret="
        + ControlPlaneSignedReadOnlyCredentialTest.CONTROL_SECRET,
    "orderpilot.security.control-plane-auth.audience=orderpilot-control-plane",
    "orderpilot.security.control-plane-auth.status=ENABLED",
    "orderpilot.security.control-plane-auth.valid-from=2026-01-01T00:00:00Z",
    "orderpilot.security.control-plane-auth.expires-at=2099-01-01T00:00:00Z",
    "orderpilot.security.control-plane-auth.permissions=STAFF_CONTROL_READ",
    "orderpilot.security.control-plane-auth.key-version=control-v1",
    "orderpilot.security.gateway-header-auth.clock-skew-seconds=300"
})
class ControlPlaneSignedReadOnlyCredentialTest {
  static final String GATEWAY_SECRET =
      "a3f91c7e2b4d8056e1a9c0d4f7b26385e6a1d9c2b4f70835a6e9c1d2b3f40517";
  static final String CONTROL_SECRET =
      "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
  static final String CREDENTIAL = "ops-prod";
  private static final String STATUS = "/api/v1/internal/control/status";
  private static final String DIAGNOSTICS = "/api/v1/internal/control/diagnostics";
  private static final String EVENTS = "/api/v1/internal/control/operational-events";

  @Autowired private MockMvc mockMvc;

  @MockBean private ControlPlaneStatusService statusService;
  @MockBean private OperationalEventReadService eventReadService;
  @MockBean private OperationalEventRecorder eventRecorder;
  @MockBean private OperationalEventAccessAuditor eventAccessAuditor;

  @Test
  void signedReadOnlyCredentialCanReadStatus() throws Exception {
    when(statusService.status()).thenReturn(new ControlStatusResponse(
        "unknown", 12L, List.of(new DependencyStatus("database", DependencyState.UP))));

    mockMvc.perform(controlSigned(STATUS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value("unknown"));
  }

  @Test
  void signedReadOnlyCredentialCannotAccessDiagnostics() throws Exception {
    mockMvc.perform(controlSigned(DIAGNOSTICS))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    verifyNoInteractions(statusService, eventReadService, eventRecorder, eventAccessAuditor);
  }

  @Test
  void signedReadOnlyCredentialCannotReadOperationalEvents() throws Exception {
    mockMvc.perform(controlSigned(EVENTS))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    verifyNoInteractions(statusService, eventReadService, eventRecorder, eventAccessAuditor);
  }

  private static MockHttpServletRequestBuilder controlSigned(String path) throws Exception {
    long now = Instant.now().getEpochSecond();
    String nonce = "nonce-" + UUID.randomUUID();
    String canonical = ControlPlaneProtocol.canonical(
        HttpMethod.GET.name(),
        path,
        "",
        "",
        ControlPlaneProtocol.EMPTY_BODY_SHA256_HEX,
        ControlPlaneProtocol.AUDIENCE,
        CREDENTIAL,
        now,
        nonce);
    return request(HttpMethod.GET, path)
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
