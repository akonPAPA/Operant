package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.channel.ChannelEventNormalizationService;
import com.orderpilot.application.services.channel.MetaMessengerChannelAdapter;
import com.orderpilot.application.services.channel.MetaMessengerWebhookVerifier;
import com.orderpilot.application.services.channel.TelegramChannelAdapter;
import com.orderpilot.application.services.channel.TelegramWebhookVerifier;
import com.orderpilot.application.services.channel.ViberChannelAdapter;
import com.orderpilot.application.services.channel.ViberWebhookVerifier;
import com.orderpilot.application.services.channel.WeChatChannelAdapter;
import com.orderpilot.application.services.channel.WeChatWebhookVerifier;
import com.orderpilot.application.services.channel.WhatsAppChannelAdapter;
import com.orderpilot.application.services.channel.WhatsAppWebhookVerifier;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.tenant.TenantContextFilter;
import com.orderpilot.domain.channel.ChannelConnection;
import com.orderpilot.domain.channel.ChannelConnectionRepository;
import com.orderpilot.domain.channel.ChannelProviderType;
import com.orderpilot.domain.channel.InboundChannelEvent;
import com.orderpilot.domain.channel.InboundChannelEventRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.ApiSecurityWebConfig;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.RequestBody;

/**
 * OP-CAP-42K — Path-2 webhook raw-body verification <b>contract regression guard</b>.
 *
 * <p>OP-CAP-42J fixed the Meta-Messenger Path-2 byte-exact signature bug: the Meta endpoint now binds the
 * raw {@code @RequestBody String} and {@link ChannelEventNormalizationService} verifies
 * {@code X-Hub-Signature-256} against the byte-exact wire body via the raw-body
 * {@code normalize(UUID, ChannelProviderType, String, Map)} overload, parsing JSON only after verification.
 *
 * <p>This class is the <b>regression fence</b> against silently reverting to the canonicalization bug — it
 * fails if any of the following stops holding:
 * <ul>
 *   <li><b>Structural</b> — the {@code metaMessenger} controller handler stops binding a raw {@code String}
 *       body (e.g. someone changes it back to a parsed {@code JsonNode}/{@code Map}); and the service still
 *       exposes the raw-body {@code normalize(String, ...)} entry point.</li>
 *   <li><b>Behavioural (canonicalization)</b> — a signature computed over the canonical/compact form does
 *       NOT verify a semantically-identical but byte-different (whitespaced) raw body. If the service ever
 *       re-canonicalized the Meta body before verifying, this whitespaced body would falsely pass.</li>
 *   <li><b>Positive sanity</b> — a signature over the exact raw bytes still verifies and persists.</li>
 *   <li><b>Fail-closed</b> — a remaining provider (Viber) stays fail-closed even with a signature-looking
 *       header, while Meta is configured.</li>
 *   <li><b>Redaction</b> — rejected responses never echo the raw body / signature / secret / internals.</li>
 * </ul>
 */
@WebMvcTest(ChannelWebhookController.class)
@Import({
    CoreConfiguration.class,
    GlobalExceptionHandler.class,
    ApiSecurityWebConfig.class,
    NoopApiPermissionTestConfig.class,
    TenantContextFilter.class,
    ChannelEventNormalizationService.class,
    TelegramWebhookVerifier.class,
    WhatsAppWebhookVerifier.class,
    ViberWebhookVerifier.class,
    WeChatWebhookVerifier.class,
    MetaMessengerWebhookVerifier.class,
    TelegramChannelAdapter.class,
    WhatsAppChannelAdapter.class,
    ViberChannelAdapter.class,
    WeChatChannelAdapter.class,
    MetaMessengerChannelAdapter.class
})
@TestPropertySource(properties = {
    "orderpilot.security.cors.allowed-origins=http://localhost:3000",
    "orderpilot.channel-gateway.meta-messenger.app-secret=op-cap-42k-meta-app-secret"
})
class ChannelWebhookMetaRawBodyContractRegressionTest {
  private static final String TENANT_ID = "11111111-1111-1111-1111-111111111111";
  private static final UUID TENANT_UUID = UUID.fromString(TENANT_ID);
  private static final String META_SECRET = "op-cap-42k-meta-app-secret";

  // The same Meta envelope rendered two ways: compact (canonical) and whitespaced. The JSON is identical
  // and the key order is preserved, so the canonical re-serialization of WHITESPACED_BODY equals
  // COMPACT_BODY — only the raw bytes differ.
  private static final String COMPACT_BODY =
      "{\"object\":\"page\",\"entry\":[{\"id\":\"PAGE_ID\",\"messaging\":[{\"message\":{\"mid\":\"m_evt-42k\",\"text\":\"Need brake pads\"}}]}]}";
  private static final String WHITESPACED_BODY =
      "{ \"object\": \"page\", \"entry\": [ { \"id\": \"PAGE_ID\", \"messaging\": [ { \"message\": { \"mid\": \"m_evt-42k\", \"text\": \"Need brake pads\" } } ] } ] }";

  private static final String[] SENSITIVE_LEAK_TOKENS = {
      "java.", "org.springframework", "com.fasterxml.jackson", "jakarta.",
      "Hibernate", "SQLException", "PSQLException", "DataAccessException",
      "stackTrace", "at com.orderpilot", ".java:", "Caused by",
      "password", "secret", "credential", "token",
      "private key", "connector credentials", "raw signing secret", "raw attacker signature"
  };

  @Autowired private MockMvc mockMvc;
  @MockBean private ChannelConnectionRepository connectionRepository;
  @MockBean private InboundChannelEventRepository eventRepository;
  @MockBean private AuditEventService auditEventService;

  private void assertNoSensitiveLeak(String body) {
    for (String token : SENSITIVE_LEAK_TOKENS) {
      assertThat(body)
          .as("response body must not leak sensitive/implementation token '%s'", token)
          .doesNotContain(token);
    }
  }

  private static String route(String provider, UUID connectionId) {
    return "/api/v1/webhooks/channels/" + provider + "/" + connectionId;
  }

  private ChannelConnection activeConnection(ChannelProviderType providerType, String mode) {
    ChannelConnection connection = Mockito.mock(ChannelConnection.class);
    Mockito.when(connection.getId()).thenReturn(UUID.randomUUID());
    Mockito.when(connection.getTenantId()).thenReturn(TENANT_UUID);
    Mockito.when(connection.getProviderType()).thenReturn(providerType);
    Mockito.when(connection.getStatus()).thenReturn("ACTIVE");
    Mockito.when(connection.getWebhookVerificationMode()).thenReturn(mode);
    Mockito.when(connection.getSecretReferenceId()).thenReturn("vault-ref");
    Mockito.when(connection.getSecretRef()).thenReturn("vault-ref");
    return connection;
  }

  /** HMAC-SHA256 over the EXACT bytes given — the only contract-correct way to sign a Meta body. */
  private static String metaSignatureOverExactBytes(String body) throws Exception {
    return "sha256=" + hmacSha256Hex(META_SECRET, body);
  }

  private static String hmacSha256Hex(String secret, String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
    StringBuilder builder = new StringBuilder(digest.length * 2);
    for (byte b : digest) {
      builder.append(String.format("%02x", b & 0xff));
    }
    return builder.toString();
  }

  // ============================================================================================
  // Structural guard — the Meta endpoint must bind the raw String body; the service must keep the
  // raw-body normalize(String, ...) entry. Reverting either reintroduces the canonicalization path.
  // ============================================================================================

  @Test
  void metaMessengerHandlerBindsRawStringBodyNotParsedJson() {
    Method[] handlers = Arrays.stream(ChannelWebhookController.class.getDeclaredMethods())
        .filter(m -> m.getName().equals("metaMessenger"))
        .toArray(Method[]::new);
    assertThat(handlers)
        .as("exactly one metaMessenger handler must exist")
        .hasSize(1);

    Parameter bodyParam = Arrays.stream(handlers[0].getParameters())
        .filter(p -> p.isAnnotationPresent(RequestBody.class))
        .findFirst()
        .orElseThrow(() -> new AssertionError("metaMessenger must declare a @RequestBody parameter"));

    assertThat(bodyParam.getType())
        .as("OP-CAP-42J/42K: Meta @RequestBody must be the byte-exact raw String body, never a parsed "
            + "JsonNode/Map (parsing+re-serialization would recreate the canonicalization signature bug)")
        .isEqualTo(String.class);
  }

  @Test
  void normalizationServiceExposesRawBodyNormalizeOverload() throws Exception {
    Method rawBodyEntry = ChannelEventNormalizationService.class.getDeclaredMethod(
        "normalize", UUID.class, ChannelProviderType.class, String.class, Map.class);
    assertThat(rawBodyEntry)
        .as("OP-CAP-42J/42K: the raw-body normalize(UUID, ChannelProviderType, String, Map) entry must "
            + "remain so cryptographic Meta verification checks the byte-exact wire body")
        .isNotNull();
  }

  // ============================================================================================
  // Behavioural canonicalization guard — a signature over the canonical/compact form must NOT verify a
  // byte-different (whitespaced) raw body. If the service re-canonicalized before verifying, this would
  // falsely pass (the canonical re-serialization of WHITESPACED_BODY equals COMPACT_BODY).
  // ============================================================================================

  @Test
  void canonicalFormSignatureDoesNotVerifyByteDifferentRawBody() throws Exception {
    // Sanity: the two bodies are byte-different but canonicalize to the same JSON.
    String canonicalOfWhitespaced = new ObjectMapper().readTree(WHITESPACED_BODY).toString();
    assertThat(WHITESPACED_BODY).isNotEqualTo(COMPACT_BODY);
    assertThat(canonicalOfWhitespaced).isEqualTo(COMPACT_BODY);

    UUID connectionId = UUID.randomUUID();
    ChannelConnection connection = activeConnection(ChannelProviderType.META_MESSENGER, "SIGNATURE_HEADER");
    Mockito.when(connectionRepository.findByIdAndTenantId(connectionId, TENANT_UUID))
        .thenReturn(Optional.of(connection));

    // Signature over the canonical/compact bytes, but the raw body actually sent is the whitespaced form.
    String signatureOverCanonical = metaSignatureOverExactBytes(COMPACT_BODY);

    String body = mockMvc.perform(post(route("meta-messenger", connectionId))
            .header("X-Tenant-Id", TENANT_ID)
            .header("X-Hub-Signature-256", signatureOverCanonical)
            .contentType(MediaType.APPLICATION_JSON)
            .content(WHITESPACED_BODY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.message").value("Webhook verification failed"))
        .andReturn().getResponse().getContentAsString();

    verify(eventRepository, never()).save(any());
    verify(auditEventService).record(eq("CHANNEL_WEBHOOK_VERIFICATION_FAILED"), any(), any(), any(), any());
    assertThat(body).doesNotContain(signatureOverCanonical);
    assertNoSensitiveLeak(body);
  }

  // ============================================================================================
  // Positive sanity — a signature over the EXACT raw bytes still verifies and persists.
  // ============================================================================================

  @Test
  void exactRawBodySignatureStillVerifiesAndPersists() throws Exception {
    UUID connectionId = UUID.randomUUID();
    ChannelConnection connection = activeConnection(ChannelProviderType.META_MESSENGER, "SIGNATURE_HEADER");
    Mockito.when(connectionRepository.findByIdAndTenantId(connectionId, TENANT_UUID))
        .thenReturn(Optional.of(connection));
    Mockito.when(eventRepository.findFirstByTenantIdAndProviderTypeAndExternalEventId(any(), any(), any()))
        .thenReturn(Optional.empty());
    Mockito.when(eventRepository.findFirstByTenantIdAndChannelConnectionIdAndPayloadHash(any(), any(), any()))
        .thenReturn(Optional.empty());
    InboundChannelEvent persisted = Mockito.mock(InboundChannelEvent.class);
    Mockito.when(persisted.getId()).thenReturn(UUID.randomUUID());
    Mockito.when(persisted.getProviderType()).thenReturn(ChannelProviderType.META_MESSENGER);
    Mockito.when(persisted.getVerificationStatus()).thenReturn("CONFIGURED_VERIFY_ONLY");
    Mockito.when(eventRepository.save(any(InboundChannelEvent.class))).thenReturn(persisted);

    mockMvc.perform(post(route("meta-messenger", connectionId))
            .header("X-Tenant-Id", TENANT_ID)
            .header("X-Hub-Signature-256", metaSignatureOverExactBytes(WHITESPACED_BODY))
            .contentType(MediaType.APPLICATION_JSON)
            .content(WHITESPACED_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verificationStatus").value("CONFIGURED_VERIFY_ONLY"));

    verify(eventRepository).save(any(InboundChannelEvent.class));
    verify(auditEventService).record(eq("CHANNEL_WEBHOOK_ACCEPTED"), any(), any(), any(), any());
  }

  // ============================================================================================
  // Fail-closed — a remaining provider stays fail-closed with a signature-looking header even while Meta
  // is configured, and the rejection leaks nothing.
  // ============================================================================================

  @Test
  void remainingProviderStaysFailClosedWithSignatureLookingHeader() throws Exception {
    UUID connectionId = UUID.randomUUID();
    ChannelConnection connection = activeConnection(ChannelProviderType.VIBER, "SIGNATURE_HEADER");
    Mockito.when(connectionRepository.findByIdAndTenantId(connectionId, TENANT_UUID))
        .thenReturn(Optional.of(connection));

    String body = mockMvc.perform(post(route("viber", connectionId))
            .header("X-Tenant-Id", TENANT_ID)
            .header("X-Hub-Signature-256", "sha256=deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"event\":\"message\",\"message_token\":\"v-42k\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Webhook verification failed"))
        .andReturn().getResponse().getContentAsString();

    verify(eventRepository, never()).save(any());
    assertThat(body).doesNotContain("deadbeef");
    assertNoSensitiveLeak(body);
  }
}
