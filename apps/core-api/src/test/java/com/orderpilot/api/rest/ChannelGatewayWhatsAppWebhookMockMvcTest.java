package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.application.services.channel.ChannelGatewayService;
import com.orderpilot.application.services.channel.WhatsAppInboundAdapter;
import com.orderpilot.application.services.channel.WhatsAppSignatureVerifier;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.tenant.TenantContextFilter;
import com.orderpilot.domain.intake.ChannelMessage;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.ApiSecurityWebConfig;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OP-CAP-42G — WhatsApp webhook end-to-end MockMvc ingress proof.
 *
 * <p>Drives the <b>real</b> HTTP route {@code POST /api/v1/channel-gateway/whatsapp/webhook} through the
 * real MVC stack (real {@link WhatsAppSignatureVerifier} with a configured test-only HMAC secret, real
 * {@link WhatsAppInboundAdapter}, real {@link GlobalExceptionHandler} error contract, real
 * {@link TenantContextFilter} tenant resolution). {@link ChannelGatewayService} is mocked so we can prove
 * — at the HTTP boundary — whether the trusted business-write service is invoked.
 *
 * <p>The verifier is loaded as the real Spring bean (not hand-constructed) with the production property
 * {@code orderpilot.channel-gateway.whatsapp.app-secret} set to a deterministic test secret. This also
 * proves the production wiring actually honours the configured secret (server-owned enforcement).
 *
 * <ul>
 *   <li>(A) Missing signature → REJECTED ack, {@code ChannelGatewayService} never invoked.
 *   <li>(B) Bad signature → REJECTED ack, no attacker-signature echo, service never invoked.
 *   <li>(C) Valid HMAC-SHA256 signature over the exact body → accepted, service invoked, no external call.
 *   <li>(D) Unknown/unsupported event with a VALID signature → IGNORED, no normalized message, service
 *       never invoked (no trusted mutation).
 *   <li>(Malformed) Structurally-invalid JSON body → stable redacted 400 via the MVC error contract.
 *   <li>(Tenant) Missing {@code X-Tenant-Id} → stable redacted 400 TENANT_REQUIRED, service never invoked.
 *   <li>(Leak) Every rejected/error body is free of internal/implementation/secret tokens.
 * </ul>
 */
@WebMvcTest(ChannelGatewayController.class)
@Import({
    CoreConfiguration.class,
    GlobalExceptionHandler.class,
    ApiSecurityWebConfig.class,
    NoopApiPermissionTestConfig.class,
    WhatsAppInboundAdapter.class,
    WhatsAppSignatureVerifier.class,
    TenantContextFilter.class
})
@TestPropertySource(properties = {
    "orderpilot.channel-gateway.whatsapp.app-secret=op-cap-42g-mvc-deterministic-secret",
    "orderpilot.security.cors.allowed-origins=http://localhost:3000"
})
class ChannelGatewayWhatsAppWebhookMockMvcTest {
  private static final String TEST_APP_SECRET = "op-cap-42g-mvc-deterministic-secret";
  private static final String WEBHOOK_PATH = "/api/v1/channel-gateway/whatsapp/webhook";
  private static final String TENANT_ID = "11111111-1111-1111-1111-111111111111";

  // Reject/error bodies must never expose any of these (OP-CAP-42G leak set).
  private static final String[] SENSITIVE_LEAK_TOKENS = {
      "java.", "org.springframework", "com.fasterxml.jackson", "jakarta.",
      "Hibernate", "SQLException", "PSQLException", "DataAccessException",
      "stackTrace", "at com.orderpilot", ".java:", "Caused by",
      "password", "secret", "credential", "token",
      "private key", "connector credentials", "raw signing secret", "raw attacker signature"
  };

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @MockBean private ChannelGatewayService gatewayService;

  private void assertNoSensitiveLeak(String body) {
    for (String token : SENSITIVE_LEAK_TOKENS) {
      assertThat(body)
          .as("response body must not leak sensitive/implementation token '%s'", token)
          .doesNotContain(token);
    }
  }

  // ============================================================================================
  // (A) Missing signature — server verification configured → REJECTED at HTTP level, service not hit.
  // ============================================================================================

  @Test
  void missingSignatureIsRejectedAtHttpLevelAndServiceIsNeverInvoked() throws Exception {
    String body = mockMvc.perform(post(WEBHOOK_PATH)
            .header("X-Tenant-Id", TENANT_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"object\":\"whatsapp_business_account\",\"entry\":[]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED_SIGNATURE_VERIFICATION_FAILED"))
        .andExpect(jsonPath("$.signatureVerified").value(false))
        .andExpect(jsonPath("$.signatureMode").value("FAILED"))
        .andExpect(jsonPath("$.acceptedCount").value(0))
        .andReturn().getResponse().getContentAsString();

    verifyNoInteractions(gatewayService);
    assertNoSensitiveLeak(body);
  }

  // ============================================================================================
  // (B) Bad signature — REJECTED, no attacker-signature echo, service not invoked.
  // ============================================================================================

  @Test
  void badSignatureIsRejectedWithoutEchoingAttackerSignatureAndServiceIsNeverInvoked() throws Exception {
    String attackerSignature = "sha256=deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef";

    String body = mockMvc.perform(post(WEBHOOK_PATH)
            .header("X-Tenant-Id", TENANT_ID)
            .header("X-Hub-Signature-256", attackerSignature)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"object\":\"whatsapp_business_account\",\"entry\":[]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED_SIGNATURE_VERIFICATION_FAILED"))
        .andExpect(jsonPath("$.signatureVerified").value(false))
        .andExpect(jsonPath("$.signatureMode").value("FAILED"))
        .andReturn().getResponse().getContentAsString();

    verifyNoInteractions(gatewayService);
    assertThat(body).doesNotContain(attackerSignature).doesNotContain("deadbeef");
    assertNoSensitiveLeak(body);
  }

  // ============================================================================================
  // (C) Valid signature — accepted, service invoked, no external call.
  // ============================================================================================

  @Test
  void validSignatureOverExactBodyReachesServiceAndReturnsAcceptedAck() throws Exception {
    String body = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":"
        + "{\"messages\":[{\"from\":\"77001112233\",\"id\":\"wamid.op42g.valid\",\"type\":\"text\","
        + "\"text\":{\"body\":\"need brake pads\"}}]}}]}]}";
    when(gatewayService.accept(any(), any())).thenReturn(Mockito.mock(ChannelMessage.class));

    mockMvc.perform(post(WEBHOOK_PATH)
            .header("X-Tenant-Id", TENANT_ID)
            .header("X-Hub-Signature-256", signatureFor(body))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED_INBOUND_ONLY"))
        .andExpect(jsonPath("$.signatureVerified").value(true))
        .andExpect(jsonPath("$.signatureMode").value("CONFIGURED_VERIFY_ONLY"))
        .andExpect(jsonPath("$.acceptedCount").value(1));

    verify(gatewayService).accept(any(), any());
  }

  // ============================================================================================
  // (D) Unknown / unsupported event with a VALID signature → IGNORED, no trusted mutation.
  // ============================================================================================

  @Test
  void unknownEventWithValidSignatureIsIgnoredAndServiceIsNeverInvoked() throws Exception {
    // Well-formed Meta envelope carrying an unsupported (non-text) message type, validly signed.
    String body = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":"
        + "{\"messages\":[{\"from\":\"77001112233\",\"id\":\"wamid.op42g.reaction\",\"type\":\"reaction\"}]}}]}]}";

    mockMvc.perform(post(WEBHOOK_PATH)
            .header("X-Tenant-Id", TENANT_ID)
            .header("X-Hub-Signature-256", signatureFor(body))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IGNORED_NO_SUPPORTED_MESSAGES"))
        .andExpect(jsonPath("$.acceptedCount").value(0))
        .andExpect(jsonPath("$.signatureVerified").value(true));

    verify(gatewayService, never()).accept(any(), any());
  }

  // ============================================================================================
  // (Malformed) Structurally-invalid JSON body → stable redacted 400 via the MVC error contract.
  // ============================================================================================

  @Test
  void malformedJsonBodyReturnsStableRedactedErrorWithoutInternals() throws Exception {
    String body = mockMvc.perform(post(WEBHOOK_PATH)
            .header("X-Tenant-Id", TENANT_ID)
            .header("X-Hub-Signature-256", "sha256=irrelevant")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ this-is-not-valid-json :: <<>>"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.message").value("Request body is not valid JSON"))
        .andReturn().getResponse().getContentAsString();

    verifyNoInteractions(gatewayService);
    assertThat(body)
        .doesNotContain("JsonParse")
        .doesNotContain("MismatchedInput")
        .doesNotContain("Unexpected character")
        .doesNotContain("this-is-not-valid-json")
        .doesNotContain("line:")
        .doesNotContain("column:");
    assertNoSensitiveLeak(body);
  }

  // ============================================================================================
  // (Tenant) Missing X-Tenant-Id → stable redacted 400 TENANT_REQUIRED, service never invoked.
  // ============================================================================================

  @Test
  void missingTenantHeaderReturnsStableRedactedTenantRequiredError() throws Exception {
    String body = "{\"object\":\"whatsapp_business_account\",\"entry\":[]}";

    String responseBody = mockMvc.perform(post(WEBHOOK_PATH)
            .header("X-Hub-Signature-256", signatureFor(body))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"))
        .andReturn().getResponse().getContentAsString();

    verifyNoInteractions(gatewayService);
    assertNoSensitiveLeak(responseBody);
  }

  /** HMAC-SHA256 over the exact JSON body the controller forwards to the verifier ({@code JsonNode.toString()}). */
  private String signatureFor(String body) throws Exception {
    String canonical = objectMapper.readTree(body).toString();
    return "sha256=" + hmacSha256Hex(canonical);
  }

  private static String hmacSha256Hex(String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(TEST_APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        builder.append(String.format("%02x", b & 0xff));
      }
      return builder.toString();
    } catch (Exception ex) {
      throw new IllegalStateException("test unable to compute HMAC", ex);
    }
  }
}
