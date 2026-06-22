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
 * OP-CAP-42H — WhatsApp webhook RAW-BODY signature exactness proof (HTTP/MVC level).
 *
 * <p>OP-CAP-42G proved the webhook ingress fails closed, but the controller still verified the signature
 * over the <b>re-serialized</b> JSON ({@code JsonNode.toString()}). Real Meta signatures are computed over
 * the <b>exact raw request body bytes</b>. This drives the real route
 * {@code POST /api/v1/channel-gateway/whatsapp/webhook} through the real MVC stack (real
 * {@link WhatsAppSignatureVerifier} with a configured test-only HMAC secret, real
 * {@link WhatsAppInboundAdapter}, real {@link GlobalExceptionHandler}/{@link TenantContextFilter}) with
 * {@link ChannelGatewayService} mocked, and proves the controller now verifies against the exact raw bytes.
 *
 * <ul>
 *   <li>(A+) Two semantically-identical but byte-different bodies (compact vs. pretty-printed) each verify
 *       against a signature over <b>their own</b> exact bytes — accepted, service invoked.
 *   <li>(B-) A body presented with a signature computed over a byte-different (but semantically identical)
 *       form is rejected — cross-body signatures do not pass.
 *   <li>(B- reserialization) A signature computed over the <b>re-serialized</b> (canonical) form does NOT
 *       verify the original whitespaced raw body — proving the old reserialize-then-sign behaviour can no
 *       longer accidentally pass, and the trusted service is never invoked.
 *   <li>(Leak) Every rejected body is free of internal/implementation/secret tokens.
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
    "orderpilot.channel-gateway.whatsapp.app-secret=op-cap-42h-rawbody-deterministic-secret",
    "orderpilot.security.cors.allowed-origins=http://localhost:3000"
})
class ChannelGatewayWhatsAppRawBodyExactnessMockMvcTest {
  private static final String TEST_APP_SECRET = "op-cap-42h-rawbody-deterministic-secret";
  private static final String WEBHOOK_PATH = "/api/v1/channel-gateway/whatsapp/webhook";
  private static final String TENANT_ID = "11111111-1111-1111-1111-111111111111";

  // A semantically identical Meta envelope rendered two ways: compact (no spaces) and whitespaced/pretty.
  // The JSON is identical; only the raw bytes differ.
  private static final String COMPACT_BODY =
      "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":"
      + "{\"messages\":[{\"from\":\"77001112233\",\"id\":\"wamid.op42h.exact\",\"type\":\"text\","
      + "\"text\":{\"body\":\"need brake pads\"}}]}}]}]}";
  private static final String PRETTY_BODY =
      "{\n"
      + "  \"object\" : \"whatsapp_business_account\",\n"
      + "  \"entry\" : [ {\n"
      + "    \"changes\" : [ {\n"
      + "      \"value\" : {\n"
      + "        \"messages\" : [ {\n"
      + "          \"from\" : \"77001112233\",\n"
      + "          \"id\" : \"wamid.op42h.exact\",\n"
      + "          \"type\" : \"text\",\n"
      + "          \"text\" : { \"body\" : \"need brake pads\" }\n"
      + "        } ]\n"
      + "      }\n"
      + "    } ]\n"
      + "  } ]\n"
      + "}";

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
  // (A+) Each byte-different body verifies against a signature over ITS OWN exact bytes.
  // ============================================================================================

  @Test
  void compactBodyVerifiesAgainstSignatureOverItsOwnExactBytes() throws Exception {
    when(gatewayService.accept(any(), any())).thenReturn(Mockito.mock(ChannelMessage.class));

    mockMvc.perform(post(WEBHOOK_PATH)
            .header("X-Tenant-Id", TENANT_ID)
            .header("X-Hub-Signature-256", signatureFor(COMPACT_BODY))
            .contentType(MediaType.APPLICATION_JSON)
            .content(COMPACT_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED_INBOUND_ONLY"))
        .andExpect(jsonPath("$.signatureVerified").value(true))
        .andExpect(jsonPath("$.signatureMode").value("CONFIGURED_VERIFY_ONLY"))
        .andExpect(jsonPath("$.acceptedCount").value(1));

    verify(gatewayService).accept(any(), any());
  }

  @Test
  void prettyBodyVerifiesAgainstSignatureOverItsOwnExactBytes() throws Exception {
    when(gatewayService.accept(any(), any())).thenReturn(Mockito.mock(ChannelMessage.class));

    mockMvc.perform(post(WEBHOOK_PATH)
            .header("X-Tenant-Id", TENANT_ID)
            .header("X-Hub-Signature-256", signatureFor(PRETTY_BODY))
            .contentType(MediaType.APPLICATION_JSON)
            .content(PRETTY_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED_INBOUND_ONLY"))
        .andExpect(jsonPath("$.signatureVerified").value(true))
        .andExpect(jsonPath("$.acceptedCount").value(1));

    verify(gatewayService).accept(any(), any());
  }

  // ============================================================================================
  // (B-) A cross-body signature (compact signature against the pretty raw body) is rejected.
  // ============================================================================================

  @Test
  void prettyBodyWithCompactBodySignatureIsRejectedAndServiceNeverInvoked() throws Exception {
    String body = mockMvc.perform(post(WEBHOOK_PATH)
            .header("X-Tenant-Id", TENANT_ID)
            .header("X-Hub-Signature-256", signatureFor(COMPACT_BODY)) // signature over the OTHER byte form
            .contentType(MediaType.APPLICATION_JSON)
            .content(PRETTY_BODY))
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
  // (B- reserialization) A signature over the RE-SERIALIZED form does not verify the raw whitespaced body.
  // This is the direct proof the OP-CAP-42G reserialize-then-sign behaviour can no longer pass.
  // ============================================================================================

  @Test
  void reserializedSignatureDoesNotPassForWhitespacedRawBody() throws Exception {
    // The signature is computed over the canonical re-serialized form (what the old controller signed).
    String reserialized = objectMapper.readTree(PRETTY_BODY).toString();
    // Sanity: re-serialization really did change the bytes vs. the raw pretty body.
    assertThat(reserialized).isNotEqualTo(PRETTY_BODY);

    String body = mockMvc.perform(post(WEBHOOK_PATH)
            .header("X-Tenant-Id", TENANT_ID)
            .header("X-Hub-Signature-256", "sha256=" + hmacSha256Hex(reserialized))
            .contentType(MediaType.APPLICATION_JSON)
            .content(PRETTY_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED_SIGNATURE_VERIFICATION_FAILED"))
        .andExpect(jsonPath("$.signatureVerified").value(false))
        .andExpect(jsonPath("$.signatureMode").value("FAILED"))
        .andReturn().getResponse().getContentAsString();

    verify(gatewayService, never()).accept(any(), any());
    assertNoSensitiveLeak(body);
  }

  /** HMAC-SHA256 over the EXACT raw body bytes (no reserialization). */
  private String signatureFor(String body) {
    return "sha256=" + hmacSha256Hex(body);
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
