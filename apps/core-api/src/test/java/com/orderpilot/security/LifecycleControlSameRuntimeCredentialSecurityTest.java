package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.domain.control.LifecycleOperationRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

/** One-runtime proof for separated staff and lifecycle-executor credentials. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = {
    "orderpilot.security.gateway-header-auth.enabled=true",
    "orderpilot.security.gateway-header-auth.signature-required=true",
    "orderpilot.security.gateway-header-auth.shared-secret="
        + LifecycleControlSameRuntimeCredentialSecurityTest.GATEWAY_SECRET,
    "orderpilot.security.gateway-header-auth.clock-skew-seconds=300",
    "orderpilot.security.control-plane-auth.principal-id=staff:operations",
    "orderpilot.security.control-plane-auth.credential-alias="
        + LifecycleControlSameRuntimeCredentialSecurityTest.STAFF_ALIAS,
    "orderpilot.security.control-plane-auth.shared-secret="
        + LifecycleControlSameRuntimeCredentialSecurityTest.STAFF_SECRET,
    "orderpilot.security.control-plane-auth.audience=orderpilot-control-plane",
    "orderpilot.security.control-plane-auth.status=ENABLED",
    "orderpilot.security.control-plane-auth.valid-from=2026-01-01T00:00:00Z",
    "orderpilot.security.control-plane-auth.expires-at=2099-01-01T00:00:00Z",
    "orderpilot.security.control-plane-auth.permissions=STAFF_CONTROL_BACKUP,STAFF_CONTROL_LIFECYCLE_READ",
    "orderpilot.security.control-plane-auth.key-version=staff-v1",
    "orderpilot.security.control-plane-auth.executor.principal-id=executor:lifecycle-primary",
    "orderpilot.security.control-plane-auth.executor.credential-alias="
        + LifecycleControlSameRuntimeCredentialSecurityTest.EXECUTOR_ALIAS,
    "orderpilot.security.control-plane-auth.executor.shared-secret="
        + LifecycleControlSameRuntimeCredentialSecurityTest.EXECUTOR_SECRET,
    "orderpilot.security.control-plane-auth.executor.audience=orderpilot-control-plane",
    "orderpilot.security.control-plane-auth.executor.status=ENABLED",
    "orderpilot.security.control-plane-auth.executor.valid-from=2026-01-01T00:00:00Z",
    "orderpilot.security.control-plane-auth.executor.expires-at=2099-01-01T00:00:00Z",
    "orderpilot.security.control-plane-auth.executor.permissions=CONTROL_EXECUTOR_LEASE,CONTROL_EXECUTOR_REPORT",
    "orderpilot.security.control-plane-auth.executor.key-version=executor-v1",
    "orderpilot.control.lifecycle.executor.enabled=true",
    "orderpilot.control.lifecycle.executor.lease-seconds=300"
})
class LifecycleControlSameRuntimeCredentialSecurityTest {
  static final String GATEWAY_SECRET =
      "a3f91c7e2b4d8056e1a9c0d4f7b26385e6a1d9c2b4f70835a6e9c1d2b3f40517";
  static final String STAFF_SECRET =
      "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
  static final String EXECUTOR_SECRET =
      "11112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
  static final String STAFF_ALIAS = "ops-staff";
  static final String EXECUTOR_ALIAS = "ops-executor";
  private static final String BASE = "/api/v1/internal/control/lifecycle";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private LifecycleOperationRepository repository;

  @Test
  void staffRequestExecutorLeaseAndCompleteThenStaffReadWorksInOneRuntime() throws Exception {
    mockMvc.perform(signed(STAFF_ALIAS, STAFF_SECRET, "POST", BASE + "/executor/lease", null))
        .andExpect(status().isUnauthorized());
    assertThat(repository.count()).isZero();

    String wrongPlaneBody = "{\"idempotencyKey\":\"wrong-plane-attempt\"}";
    mockMvc.perform(signed(
            EXECUTOR_ALIAS, EXECUTOR_SECRET, "POST", BASE + "/backups", wrongPlaneBody))
        .andExpect(status().isUnauthorized());
    assertThat(repository.count()).isZero();

    String requestBody = "{\"idempotencyKey\":\"same-runtime-flow-1\"}";
    MvcResult requested = mockMvc.perform(
            signed(STAFF_ALIAS, STAFF_SECRET, "POST", BASE + "/backups", requestBody))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.state").value("QUEUED"))
        .andReturn();
    String operationId = json(requested).path("operationId").asText();
    assertThat(operationId).startsWith("op_");
    assertThat(repository.count()).isEqualTo(1);

    mockMvc.perform(signed(STAFF_ALIAS, STAFF_SECRET, "GET", BASE + "/operations/" + operationId, null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("QUEUED"));

    MvcResult leased = mockMvc.perform(
            signed(EXECUTOR_ALIAS, EXECUTOR_SECRET, "POST", BASE + "/executor/lease", null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.operationId").value(operationId))
        .andExpect(jsonPath("$.fencingToken").value(1))
        .andReturn();
    long fencingToken = json(leased).path("fencingToken").asLong();

    String completionBody = "{\"fencingToken\":" + fencingToken
        + ",\"resultCode\":\"BACKUP_COMPLETED\"}";
    mockMvc.perform(signed(
            EXECUTOR_ALIAS,
            EXECUTOR_SECRET,
            "POST",
            BASE + "/operations/" + operationId + "/complete",
            completionBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("SUCCEEDED"))
        .andExpect(jsonPath("$.resultCode").value("BACKUP_COMPLETED"));

    mockMvc.perform(signed(STAFF_ALIAS, STAFF_SECRET, "GET", BASE + "/operations/" + operationId, null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("SUCCEEDED"))
        .andExpect(jsonPath("$.resultCode").value("BACKUP_COMPLETED"));
  }

  private JsonNode json(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsByteArray());
  }

  private static MockHttpServletRequestBuilder signed(
      String alias,
      String secret,
      String method,
      String path,
      String body) throws Exception {
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
        alias,
        now,
        nonce);
    MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path)
        .header(ControlPlaneProtocol.CREDENTIAL_HEADER, alias)
        .header(ControlPlaneProtocol.AUDIENCE_HEADER, ControlPlaneProtocol.AUDIENCE)
        .header(ControlPlaneProtocol.TIMESTAMP_HEADER, Long.toString(now))
        .header(ControlPlaneProtocol.NONCE_HEADER, nonce)
        .header(ControlPlaneProtocol.VERSION_HEADER, ControlPlaneProtocol.SIGNATURE_VERSION)
        .header(ControlPlaneProtocol.CONTENT_SHA256_HEADER, bodySha)
        .header(ControlPlaneProtocol.SIGNATURE_HEADER, hmac(secret, canonical));
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
