package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.nio.charset.StandardCharsets;
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

/**
 * OP-CAP-42I — Path-2 {@link ChannelWebhookController} Meta Messenger <b>verified</b> end-to-end proof
 * (HTTP/MVC level) with a server-configured Meta app secret.
 *
 * <p>Drives the real public route {@code POST /api/v1/webhooks/channels/meta-messenger/{connectionId}}
 * through the real MVC stack and the <b>real</b> {@link ChannelEventNormalizationService} + real
 * {@link MetaMessengerWebhookVerifier} (configured test-only HMAC secret via {@code @TestPropertySource})
 * + real adapter + real {@link GlobalExceptionHandler}. Persistence and {@link AuditEventService} are
 * mocked so the MVC→service→verifier→persist boundary is exercised without a DB.
 *
 * <ul>
 *   <li>Valid Meta {@code X-Hub-Signature-256} over the byte-exact raw body → {@code 200}, persisted event
 *       carries {@code verificationStatus=CONFIGURED_VERIFY_ONLY}, audited {@code CHANNEL_WEBHOOK_ACCEPTED}.</li>
 *   <li>(OP-CAP-42J) Same semantic JSON but byte-different (whitespace/key-order) carrying the old
 *       signature → fails closed before persistence; a whitespace body signed over its exact bytes is
 *       accepted.</li>
 *   <li>Missing / bad signature → fails closed (redacted {@code 400}), no event persisted, audited
 *       {@code CHANNEL_WEBHOOK_VERIFICATION_FAILED}, attacker signature not echoed, no leak.</li>
 *   <li>Replay: a valid-signed delivery whose {@code externalEventId} already exists is deduped by the
 *       existing service-level guard — returns the existing event, no second persist.</li>
 *   <li>Remaining providers (Viber) still fail closed even while Meta is configured.</li>
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
    "orderpilot.channel-gateway.meta-messenger.app-secret=op-cap-42i-meta-app-secret"
})
class ChannelWebhookControllerMetaVerifiedMockMvcTest {
  private static final String TENANT_ID = "11111111-1111-1111-1111-111111111111";
  private static final UUID TENANT_UUID = UUID.fromString(TENANT_ID);
  private static final String META_SECRET = "op-cap-42i-meta-app-secret";
  private static final String META_BODY =
      "{\"object\":\"page\",\"entry\":[{\"id\":\"PAGE_ID\",\"messaging\":[{\"message\":{\"mid\":\"m_evt-42i\",\"text\":\"Need brake pads\"}}]}]}";

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

  /**
   * OP-CAP-42J — signature over the <b>byte-exact</b> request body the controller will receive. Real Meta
   * {@code X-Hub-Signature-256} is computed over the raw wire bytes, and the controller now verifies
   * against those exact bytes (not a canonical re-serialization).
   */
  private String metaSignatureFor(String requestBody) throws Exception {
    return "sha256=" + hmacSha256Hex(META_SECRET, requestBody);
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
  // Positive — valid Meta signature → verified accept, event persisted as CONFIGURED_VERIFY_ONLY.
  // ============================================================================================

  @Test
  void validMetaSignatureIsAcceptedAndPersistedAsConfiguredVerifyOnly() throws Exception {
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

    String body = mockMvc.perform(post(route("meta-messenger", connectionId))
            .header("X-Tenant-Id", TENANT_ID)
            .header("X-Hub-Signature-256", metaSignatureFor(META_BODY))
            .contentType(MediaType.APPLICATION_JSON)
            .content(META_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verificationStatus").value("CONFIGURED_VERIFY_ONLY"))
        .andReturn().getResponse().getContentAsString();

    verify(eventRepository).save(any(InboundChannelEvent.class));
    verify(auditEventService).record(eq("CHANNEL_WEBHOOK_ACCEPTED"), any(), any(), any(), any());
    assertNoSensitiveLeak(body);
  }

  @Test
  void whitespaceFormattedBodySignedOverItsExactBytesIsAcceptedAndPersisted() throws Exception {
    // A body with embedded whitespace (byte-different from the compact form) is signed over its exact
    // bytes. Byte-exact verification accepts it — proving the controller verifies the raw wire body.
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

    String whitespacedBody =
        "{ \"object\": \"page\", \"entry\": [ { \"id\": \"PAGE_ID\", \"messaging\": [ { \"message\": { \"mid\": \"m_evt-42j\", \"text\": \"Need brake pads\" } } ] } ] }";

    String body = mockMvc.perform(post(route("meta-messenger", connectionId))
            .header("X-Tenant-Id", TENANT_ID)
            .header("X-Hub-Signature-256", metaSignatureFor(whitespacedBody))
            .contentType(MediaType.APPLICATION_JSON)
            .content(whitespacedBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verificationStatus").value("CONFIGURED_VERIFY_ONLY"))
        .andReturn().getResponse().getContentAsString();

    verify(eventRepository).save(any(InboundChannelEvent.class));
    verify(auditEventService).record(eq("CHANNEL_WEBHOOK_ACCEPTED"), any(), any(), any(), any());
    assertNoSensitiveLeak(body);
  }

  // ============================================================================================
  // Canonical-JSON bypass regression — same semantic JSON, byte-different (whitespace/key-order),
  // carrying a signature for the original compact bytes → rejected before any persistence.
  // ============================================================================================

  @Test
  void semanticallyEqualButByteDifferentBodyWithOldSignatureIsRejectedBeforePersistence() throws Exception {
    UUID connectionId = UUID.randomUUID();
    ChannelConnection connection = activeConnection(ChannelProviderType.META_MESSENGER, "SIGNATURE_HEADER");
    Mockito.when(connectionRepository.findByIdAndTenantId(connectionId, TENANT_UUID))
        .thenReturn(Optional.of(connection));

    // Signature is valid for the compact META_BODY bytes, but the body actually sent has whitespace and a
    // changed key order. Under byte-exact verification this old signature must fail closed.
    String oldSignatureForCompactBody = metaSignatureFor(META_BODY);
    String byteDifferentBody =
        "{ \"entry\": [ { \"messaging\": [ { \"message\": { \"text\": \"Need brake pads\", \"mid\": \"m_evt-42i\" } } ], \"id\": \"PAGE_ID\" } ], \"object\": \"page\" }";

    String body = mockMvc.perform(post(route("meta-messenger", connectionId))
            .header("X-Tenant-Id", TENANT_ID)
            .header("X-Hub-Signature-256", oldSignatureForCompactBody)
            .contentType(MediaType.APPLICATION_JSON)
            .content(byteDifferentBody))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.message").value("Webhook verification failed"))
        .andReturn().getResponse().getContentAsString();

    verify(eventRepository, never()).save(any());
    verify(auditEventService).record(eq("CHANNEL_WEBHOOK_VERIFICATION_FAILED"), any(), any(), any(), any());
    assertThat(body).doesNotContain(oldSignatureForCompactBody);
    assertNoSensitiveLeak(body);
  }

  // ============================================================================================
  // Negative — bad signature fails closed; no persist; audited failure; attacker value not echoed.
  // ============================================================================================

  @Test
  void badMetaSignatureFailsClosedAndPersistsNothing() throws Exception {
    UUID connectionId = UUID.randomUUID();
    ChannelConnection connection = activeConnection(ChannelProviderType.META_MESSENGER, "SIGNATURE_HEADER");
    Mockito.when(connectionRepository.findByIdAndTenantId(connectionId, TENANT_UUID))
        .thenReturn(Optional.of(connection));
    String hostileSignature = "sha256=deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef";

    String body = mockMvc.perform(post(route("meta-messenger", connectionId))
            .header("X-Tenant-Id", TENANT_ID)
            .header("X-Hub-Signature-256", hostileSignature)
            .contentType(MediaType.APPLICATION_JSON)
            .content(META_BODY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.message").value("Webhook verification failed"))
        .andReturn().getResponse().getContentAsString();

    verify(eventRepository, never()).save(any());
    verify(auditEventService).record(eq("CHANNEL_WEBHOOK_VERIFICATION_FAILED"), any(), any(), any(), any());
    assertThat(body).doesNotContain("deadbeef");
    assertNoSensitiveLeak(body);
  }

  @Test
  void missingMetaSignatureFailsClosedAndPersistsNothing() throws Exception {
    UUID connectionId = UUID.randomUUID();
    ChannelConnection connection = activeConnection(ChannelProviderType.META_MESSENGER, "SIGNATURE_HEADER");
    Mockito.when(connectionRepository.findByIdAndTenantId(connectionId, TENANT_UUID))
        .thenReturn(Optional.of(connection));

    String body = mockMvc.perform(post(route("meta-messenger", connectionId))
            .header("X-Tenant-Id", TENANT_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .content(META_BODY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Webhook verification failed"))
        .andReturn().getResponse().getContentAsString();

    verify(eventRepository, never()).save(any());
    assertNoSensitiveLeak(body);
  }

  // ============================================================================================
  // Replay — a valid-signed delivery whose externalEventId already exists is deduped (no second persist).
  // ============================================================================================

  @Test
  void validSignedReplayIsDedupedByExistingServiceGuardAndNotPersistedAgain() throws Exception {
    UUID connectionId = UUID.randomUUID();
    ChannelConnection connection = activeConnection(ChannelProviderType.META_MESSENGER, "SIGNATURE_HEADER");
    Mockito.when(connectionRepository.findByIdAndTenantId(connectionId, TENANT_UUID))
        .thenReturn(Optional.of(connection));
    // The same externalEventId is already stored → service returns the existing event before any save.
    InboundChannelEvent existing = Mockito.mock(InboundChannelEvent.class);
    Mockito.when(existing.getId()).thenReturn(UUID.randomUUID());
    Mockito.when(existing.getProviderType()).thenReturn(ChannelProviderType.META_MESSENGER);
    Mockito.when(existing.getVerificationStatus()).thenReturn("CONFIGURED_VERIFY_ONLY");
    Mockito.when(eventRepository.findFirstByTenantIdAndProviderTypeAndExternalEventId(any(), any(), any()))
        .thenReturn(Optional.of(existing));

    mockMvc.perform(post(route("meta-messenger", connectionId))
            .header("X-Tenant-Id", TENANT_ID)
            .header("X-Hub-Signature-256", metaSignatureFor(META_BODY))
            .contentType(MediaType.APPLICATION_JSON)
            .content(META_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verificationStatus").value("CONFIGURED_VERIFY_ONLY"));

    // No duplicate business effect: the replayed delivery did not persist a second event.
    verify(eventRepository, never()).save(any());
  }

  // ============================================================================================
  // Remaining providers stay fail-closed even while Meta is configured.
  // ============================================================================================

  @Test
  void viberStillFailsClosedWhileMetaIsConfigured() throws Exception {
    UUID connectionId = UUID.randomUUID();
    ChannelConnection connection = activeConnection(ChannelProviderType.VIBER, "SIGNATURE_HEADER");
    Mockito.when(connectionRepository.findByIdAndTenantId(connectionId, TENANT_UUID))
        .thenReturn(Optional.of(connection));

    String body = mockMvc.perform(post(route("viber", connectionId))
            .header("X-Tenant-Id", TENANT_ID)
            .header("X-Viber-Content-Signature", "forged-viber-signature")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"event\":\"message\",\"message_token\":\"v-1\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Webhook verification failed"))
        .andReturn().getResponse().getContentAsString();

    verify(eventRepository, never()).save(any());
    assertThat(body).doesNotContain("forged-viber-signature");
    assertNoSensitiveLeak(body);
  }
}
