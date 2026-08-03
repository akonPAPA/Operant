package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.application.services.LegacyWebhookIngressGuard;
import com.orderpilot.application.services.channel.ChannelGatewayService;
import com.orderpilot.application.services.channel.WebhookAuthenticationException;
import com.orderpilot.application.services.channel.WebhookIntakeConnectionResolver;
import com.orderpilot.application.services.channel.WebhookVerificationAuthority;
import com.orderpilot.application.services.channel.WhatsAppInboundAdapter;
import com.orderpilot.application.services.channel.WhatsAppSignatureVerifier;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.tenant.TenantContextFilter;
import com.orderpilot.domain.channel.ChannelConnection;
import com.orderpilot.domain.channel.ChannelProviderType;
import com.orderpilot.domain.intake.ChannelMessage;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.ApiSecurityWebConfig;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OP-FD-P0-001..003 / OP-CAP-42G — WhatsApp connection-scoped webhook ingress proof.
 *
 * <p>Drives {@code POST /api/v1/channel-gateway/whatsapp/webhook/{connectionId}} through the real MVC
 * stack with a real HMAC verifier. Tenant is resolved from the server-owned connection; forged
 * {@code X-Tenant-Id} and {@code X-OrderPilot-Fixture-Mode} cannot grant authority.
 */
@WebMvcTest(ChannelGatewayController.class)
@ActiveProfiles("test")
@Import({
    CoreConfiguration.class,
    GlobalExceptionHandler.class,
    ApiSecurityWebConfig.class,
    NoopApiPermissionTestConfig.class,
    WhatsAppInboundAdapter.class,
    WhatsAppSignatureVerifier.class,
    TenantContextFilter.class,
    WebhookVerificationAuthority.class,
    LegacyWebhookIngressGuard.class
})
@TestPropertySource(properties = {
    "orderpilot.channel-gateway.whatsapp.app-secret=op-cap-42g-mvc-deterministic-secret",
    "orderpilot.security.cors.allowed-origins=http://localhost:3000"
})
class ChannelGatewayWhatsAppWebhookMockMvcTest {
  private static final String TEST_APP_SECRET = "op-cap-42g-mvc-deterministic-secret";
  private static final UUID CONNECTION_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID OWNER_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final String WEBHOOK_PATH =
      "/api/v1/channel-gateway/whatsapp/webhook/" + CONNECTION_ID;

  private static final String[] SENSITIVE_LEAK_TOKENS = {
      "java.", "org.springframework", "com.fasterxml.jackson", "jakarta.",
      "Hibernate", "SQLException", "PSQLException", "DataAccessException",
      "stackTrace", "at com.orderpilot", ".java:", "Caused by",
      "password", "secret", "credential", "token",
      "private key", "connector credentials", "raw signing secret", "raw attacker signature"
  };

  @Autowired private MockMvc mockMvc;
  @MockBean private ChannelGatewayService gatewayService;
  @MockBean private WebhookIntakeConnectionResolver connectionResolver;

  @BeforeEach
  void stubActiveWhatsAppConnection() {
    ChannelConnection connection = Mockito.mock(ChannelConnection.class);
    when(connection.getId()).thenReturn(CONNECTION_ID);
    when(connection.getTenantId()).thenReturn(OWNER_TENANT);
    when(connection.getProviderType()).thenReturn(ChannelProviderType.WHATSAPP);
    when(connection.getStatus()).thenReturn("ACTIVE");
    when(connectionResolver.resolveActiveConnection(eq(CONNECTION_ID), eq(ChannelProviderType.WHATSAPP)))
        .thenReturn(connection);
  }

  private void assertNoSensitiveLeak(String body) {
    for (String token : SENSITIVE_LEAK_TOKENS) {
      assertThat(body)
          .as("response body must not leak sensitive/implementation token '%s'", token)
          .doesNotContain(token);
    }
  }

  @Test
  void missingSignatureIsUnauthorizedAndServiceIsNeverInvoked() throws Exception {
    String body = mockMvc.perform(post(WEBHOOK_PATH)
            .header("X-Tenant-Id", "22222222-2222-2222-2222-222222222222")
            .header("X-OrderPilot-Fixture-Mode", "true")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"object\":\"whatsapp_business_account\",\"entry\":[]}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(WebhookAuthenticationException.CODE))
        .andExpect(jsonPath("$.message").value(WebhookAuthenticationException.SAFE_MESSAGE))
        .andReturn().getResponse().getContentAsString();

    verifyNoInteractions(gatewayService);
    assertNoSensitiveLeak(body);
  }

  @Test
  void badSignatureIsUnauthorizedWithoutEchoingAttackerSignature() throws Exception {
    String attackerSignature = "sha256=deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef";

    String body = mockMvc.perform(post(WEBHOOK_PATH)
            .header("X-Hub-Signature-256", attackerSignature)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"object\":\"whatsapp_business_account\",\"entry\":[]}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(WebhookAuthenticationException.CODE))
        .andReturn().getResponse().getContentAsString();

    verifyNoInteractions(gatewayService);
    assertThat(body).doesNotContain(attackerSignature).doesNotContain("deadbeef");
    assertNoSensitiveLeak(body);
  }

  @Test
  void validSignatureOverExactBodyReachesServiceAndReturnsAcceptedAck() throws Exception {
    String body = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":"
        + "{\"messages\":[{\"from\":\"77001112233\",\"id\":\"wamid.op42g.valid\",\"type\":\"text\","
        + "\"text\":{\"body\":\"need brake pads\"}}]}}]}]}";
    when(gatewayService.accept(any(), any())).thenReturn(Mockito.mock(ChannelMessage.class));

    mockMvc.perform(post(WEBHOOK_PATH)
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

  @Test
  void unknownEventWithValidSignatureIsIgnoredAndServiceIsNeverInvoked() throws Exception {
    String body = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"value\":"
        + "{\"messages\":[{\"from\":\"77001112233\",\"id\":\"wamid.op42g.reaction\",\"type\":\"reaction\"}]}}]}]}";

    mockMvc.perform(post(WEBHOOK_PATH)
            .header("X-Hub-Signature-256", signatureFor(body))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IGNORED_NO_SUPPORTED_MESSAGES"))
        .andExpect(jsonPath("$.acceptedCount").value(0))
        .andExpect(jsonPath("$.signatureVerified").value(true));

    verify(gatewayService, never()).accept(any(), any());
  }

  @Test
  void malformedJsonBodyReturnsStableRedactedErrorWithoutInternals() throws Exception {
    String malformed = "{ this-is-not-valid-json :: <<>>";
    String body = mockMvc.perform(post(WEBHOOK_PATH)
            .header("X-Hub-Signature-256", signatureFor(malformed))
            .contentType(MediaType.APPLICATION_JSON)
            .content(malformed))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.message").value("Request body is not valid JSON"))
        .andReturn().getResponse().getContentAsString();

    verifyNoInteractions(gatewayService);
    assertThat(body)
        .doesNotContain("JsonParse")
        .doesNotContain("this-is-not-valid-json");
    assertNoSensitiveLeak(body);
  }

  @Test
  void legacyUnqualifiedWhatsappRouteIsDeniedEvenInTestProfile() throws Exception {
    String response = mockMvc.perform(post("/api/v1/channel-gateway/whatsapp/webhook")
            .header("X-Tenant-Id", OWNER_TENANT.toString())
            .header("X-OrderPilot-Fixture-Mode", "true")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"object\":\"whatsapp_business_account\",\"entry\":[]}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(WebhookAuthenticationException.CODE))
        .andReturn().getResponse().getContentAsString();

    verifyNoInteractions(gatewayService);
    assertNoSensitiveLeak(response);
  }

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
