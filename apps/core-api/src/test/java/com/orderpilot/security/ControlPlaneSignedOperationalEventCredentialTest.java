package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.ControlInternalDtos.OperationalEventPage;
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
 * Signed-control proof for the dedicated operational-event permission, raw-query HMAC binding and
 * bounded pseudonymous access auditing.
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
        + ControlPlaneSignedOperationalEventCredentialTest.GATEWAY_SECRET,
    "orderpilot.security.control-plane-auth.credential-alias="
        + ControlPlaneSignedOperationalEventCredentialTest.CREDENTIAL,
    "orderpilot.security.control-plane-auth.shared-secret="
        + ControlPlaneSignedOperationalEventCredentialTest.CONTROL_SECRET,
    "orderpilot.security.control-plane-auth.audience=orderpilot-control-plane",
    "orderpilot.security.control-plane-auth.status=ENABLED",
    "orderpilot.security.control-plane-auth.valid-from=2026-01-01T00:00:00Z",
    "orderpilot.security.control-plane-auth.expires-at=2099-01-01T00:00:00Z",
    "orderpilot.security.control-plane-auth.permissions=STAFF_CONTROL_OPERATIONAL_EVENT_READ",
    "orderpilot.security.control-plane-auth.key-version=control-v1",
    "orderpilot.security.gateway-header-auth.clock-skew-seconds=300"
})
class ControlPlaneSignedOperationalEventCredentialTest {
  static final String GATEWAY_SECRET =
      "a3f91c7e2b4d8056e1a9c0d4f7b26385e6a1d9c2b4f70835a6e9c1d2b3f40517";
  static final String CONTROL_SECRET =
      "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
  static final String CREDENTIAL = "ops-prod";
  private static final String STATUS = "/api/v1/internal/control/status";
  private static final String EVENTS = "/api/v1/internal/control/operational-events";

  @Autowired private MockMvc mockMvc;

  @MockBean private ControlPlaneStatusService statusService;
  @MockBean private OperationalEventReadService eventReadService;
  @MockBean private OperationalEventRecorder eventRecorder;

  @Test
  void signedEventReadCredentialCanReadOperationalEvents() throws Exception {
    when(eventReadService.read(any(), any(), any(), any(), any())).thenReturn(emptyPage());

    mockMvc.perform(controlSigned(EVENTS, ""))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
        .andExpect(jsonPath("$.maxLimit").value(100))
        .andExpect(jsonPath("$.scope").value("LOCAL_PROCESS_RECENT_OPERATIONAL_EVENTS"))
        .andExpect(jsonPath("$.hasMore").value(false));
  }

  @Test
  void signedEventReadCredentialCannotReadStatus() throws Exception {
    mockMvc.perform(controlSigned(STATUS, ""))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    verifyNoInteractions(statusService, eventReadService, eventRecorder);
  }

  @Test
  void queryTamperedAfterSigningIsDeniedBeforeReadProducerOrSuccessAudit() throws Exception {
    ch.qos.logback.classic.Logger auditLogger = auditLogger();
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captured =
        new ch.qos.logback.core.read.ListAppender<>();
    captured.start();
    auditLogger.addAppender(captured);
    try {
      mockMvc.perform(controlSigned(EVENTS, "").with(mutable -> {
            mutable.setQueryString("limit=5");
            return mutable;
          }))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    } finally {
      auditLogger.detachAppender(captured);
    }

    verifyNoInteractions(eventReadService, eventRecorder);
    assertThat(captured.list).isEmpty();
  }

  @Test
  void successfulReadEmitsBoundedPseudonymousAuditWithoutContent() throws Exception {
    when(eventReadService.read(any(), any(), any(), any(), any())).thenReturn(emptyPage());

    ch.qos.logback.classic.Logger auditLogger = auditLogger();
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captured =
        new ch.qos.logback.core.read.ListAppender<>();
    captured.start();
    auditLogger.addAppender(captured);
    try {
      mockMvc.perform(controlSigned(EVENTS, "")).andExpect(status().isOk());
    } finally {
      auditLogger.detachAppender(captured);
    }

    assertThat(captured.list).hasSize(1);
    String message = captured.list.get(0).getFormattedMessage();
    assertThat(message)
        .contains("result=SUCCESS")
        .contains("permission=STAFF_CONTROL_OPERATIONAL_EVENT_READ")
        .contains("severityFilterPresent=false")
        .contains("customLimitPresent=false")
        .contains("returned=0")
        .matches(".*principalFingerprint=[0-9a-f]{24}.*")
        .doesNotContain(CREDENTIAL)
        .doesNotContain(CONTROL_SECRET)
        .doesNotContain("Bearer");
  }

  private static OperationalEventPage emptyPage() {
    return new OperationalEventPage(
        List.of(), null, false, 0, 100, "LOCAL_PROCESS_RECENT_OPERATIONAL_EVENTS",
        "22222222-2222-2222-2222-222222222222");
  }

  private static ch.qos.logback.classic.Logger auditLogger() {
    return (ch.qos.logback.classic.Logger)
        org.slf4j.LoggerFactory.getLogger(OperationalEventAccessAuditor.AUDIT_LOGGER_NAME);
  }

  private static MockHttpServletRequestBuilder controlSigned(String path, String rawQuery) throws Exception {
    long now = Instant.now().getEpochSecond();
    String nonce = "nonce-" + UUID.randomUUID();
    String canonical = ControlPlaneProtocol.canonical(
        HttpMethod.GET.name(),
        path,
        rawQuery,
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
