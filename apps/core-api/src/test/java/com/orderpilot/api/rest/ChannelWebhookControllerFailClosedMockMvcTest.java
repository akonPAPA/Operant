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
import java.util.Optional;
import java.util.UUID;
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
 * OP-CAP-42H — Path-2 {@link ChannelWebhookController} end-to-end fail-closed proof (HTTP/MVC level).
 *
 * <p>Drives the real public routes {@code POST /api/v1/webhooks/channels/{provider}/{connectionId}}
 * through the real MVC stack and the <b>real</b> {@link ChannelEventNormalizationService} + real provider
 * verifiers/adapters + real {@link GlobalExceptionHandler}. Persistence
 * ({@link ChannelConnectionRepository}, {@link InboundChannelEventRepository}) and
 * {@link AuditEventService} are mocked so the MVC→service→verifier boundary is exercised without a DB and
 * the absence of a trusted business mutation (no event persisted) is provable at the HTTP edge.
 *
 * <ul>
 *   <li>Unknown connection → stable redacted {@code 400}, no internals, no event persisted.
 *   <li>Enforcing {@code SIGNATURE_HEADER} mode with a present hostile signature header → fails closed for
 *       every provider (no silent fail-open), audited {@code CHANNEL_WEBHOOK_VERIFICATION_FAILED}, no event.
 *   <li>Enforcing {@code SHARED_SECRET} mode with a present hostile secret header → fails closed.
 *   <li>Missing tenant header → stable redacted {@code TENANT_REQUIRED}; persistence untouched.
 *   <li>(Local-dev) explicit {@code DISABLED_FOR_LOCAL_DEV} is accepted only as an honest
 *       {@code SKIPPED_LOCAL_DEV} skip — it never masquerades as a verified signature.
 *   <li>Every rejected/error body is free of internal/implementation/secret tokens.
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
@TestPropertySource(properties = {"orderpilot.security.cors.allowed-origins=http://localhost:3000"})
class ChannelWebhookControllerFailClosedMockMvcTest {
  private static final String TENANT_ID = "11111111-1111-1111-1111-111111111111";
  private static final UUID TENANT_UUID = UUID.fromString(TENANT_ID);

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
    when(connection.getId()).thenReturn(UUID.randomUUID());
    when(connection.getTenantId()).thenReturn(TENANT_UUID);
    when(connection.getProviderType()).thenReturn(providerType);
    when(connection.getStatus()).thenReturn("ACTIVE");
    when(connection.getWebhookVerificationMode()).thenReturn(mode);
    when(connection.getSecretReferenceId()).thenReturn("vault-ref");
    when(connection.getSecretRef()).thenReturn("vault-ref");
    return connection;
  }

  // ============================================================================================
  // Unknown / missing connection — safe rejection, no internals, no event persisted.
  // ============================================================================================

  @Test
  void unknownConnectionReturnsStableRedactedRejectionAndPersistsNothing() throws Exception {
    UUID connectionId = UUID.randomUUID();
    when(connectionRepository.findByIdAndTenantId(connectionId, TENANT_UUID)).thenReturn(Optional.empty());

    String body = mockMvc.perform(post(route("telegram", connectionId))
            .header("X-Tenant-Id", TENANT_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":{\"message_id\":\"m1\",\"text\":\"hello\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.message").value("Channel connection not found"))
        .andReturn().getResponse().getContentAsString();

    verify(eventRepository, never()).save(any());
    assertNoSensitiveLeak(body);
  }

  // ============================================================================================
  // Enforcing SIGNATURE_HEADER mode + present hostile signature → fails closed for every provider.
  // ============================================================================================

  @Test
  void signatureHeaderModeFailsClosedWithHostileSignatureForEveryProvider() throws Exception {
    String[][] providers = {
        {"telegram", "TELEGRAM"},
        {"whatsapp", "WHATSAPP"},
        {"viber", "VIBER"},
        {"wechat", "WECHAT"},
        {"meta-messenger", "META_MESSENGER"}
    };
    String hostileSignature = "sha256=deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef";

    for (String[] provider : providers) {
      UUID connectionId = UUID.randomUUID();
      ChannelProviderType providerType = ChannelProviderType.valueOf(provider[1]);
      ChannelConnection connection = activeConnection(providerType, "SIGNATURE_HEADER");
      when(connectionRepository.findByIdAndTenantId(connectionId, TENANT_UUID))
          .thenReturn(Optional.of(connection));

      String body = mockMvc.perform(post(route(provider[0], connectionId))
              .header("X-Tenant-Id", TENANT_ID)
              .header("X-Hub-Signature-256", hostileSignature)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"event\":\"message\",\"id\":\"evt-1\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
          .andExpect(jsonPath("$.message").value("Webhook verification failed"))
          .andReturn().getResponse().getContentAsString();

      assertThat(body).doesNotContain("deadbeef");
      assertNoSensitiveLeak(body);
    }

    // No trusted channel event was ever persisted for any provider.
    verify(eventRepository, never()).save(any());
    // Each rejection is audited as a verification failure (server-owned safety record).
    verify(auditEventService, Mockito.times(providers.length))
        .record(eq("CHANNEL_WEBHOOK_VERIFICATION_FAILED"), any(), any(), any(), any());
  }

  // ============================================================================================
  // Enforcing SHARED_SECRET mode + present hostile secret header → fails closed.
  // ============================================================================================

  @Test
  void sharedSecretModeFailsClosedWithPresentHostileSecretHeader() throws Exception {
    UUID connectionId = UUID.randomUUID();
    ChannelConnection connection = activeConnection(ChannelProviderType.WHATSAPP, "SHARED_SECRET");
    when(connectionRepository.findByIdAndTenantId(connectionId, TENANT_UUID))
        .thenReturn(Optional.of(connection));

    String body = mockMvc.perform(post(route("whatsapp", connectionId))
            .header("X-Tenant-Id", TENANT_ID)
            .header("x-orderpilot-webhook-secret", "forged-shared-secret")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"event\":\"message\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Webhook verification failed"))
        .andReturn().getResponse().getContentAsString();

    verify(eventRepository, never()).save(any());
    assertThat(body).doesNotContain("forged-shared-secret");
    assertNoSensitiveLeak(body);
  }

  // ============================================================================================
  // Missing tenant header → stable redacted TENANT_REQUIRED, persistence untouched.
  // ============================================================================================

  @Test
  void missingTenantHeaderReturnsStableRedactedTenantRequired() throws Exception {
    String body = mockMvc.perform(post(route("telegram", UUID.randomUUID()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":{\"message_id\":\"m1\",\"text\":\"hello\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"))
        .andReturn().getResponse().getContentAsString();

    verifyNoInteractions(connectionRepository);
    verify(eventRepository, never()).save(any());
    assertNoSensitiveLeak(body);
  }

  // ============================================================================================
  // (Local-dev) DISABLED_FOR_LOCAL_DEV is accepted only as an explicit SKIP — never "verified".
  // ============================================================================================

  @Test
  void localDevModeIsAcceptedAsExplicitSkipNotAsVerifiedSignature() throws Exception {
    UUID connectionId = UUID.randomUUID();
    ChannelConnection connection = activeConnection(ChannelProviderType.TELEGRAM, "DISABLED_FOR_LOCAL_DEV");
    when(connectionRepository.findByIdAndTenantId(connectionId, TENANT_UUID))
        .thenReturn(Optional.of(connection));
    when(eventRepository.findFirstByTenantIdAndProviderTypeAndExternalEventId(any(), any(), any()))
        .thenReturn(Optional.empty());
    when(eventRepository.findFirstByTenantIdAndChannelConnectionIdAndPayloadHash(any(), any(), any()))
        .thenReturn(Optional.empty());
    // The persisted event is server-built; the local-dev verification status is carried through to the
    // response as an explicit SKIP (the service stamps it from VerificationResult.skippedLocalDev).
    InboundChannelEvent persisted = Mockito.mock(InboundChannelEvent.class);
    when(persisted.getId()).thenReturn(UUID.randomUUID());
    when(persisted.getProviderType()).thenReturn(ChannelProviderType.TELEGRAM);
    when(persisted.getVerificationStatus()).thenReturn("SKIPPED_LOCAL_DEV");
    when(eventRepository.save(any(InboundChannelEvent.class))).thenReturn(persisted);

    String body = mockMvc.perform(post(route("telegram", connectionId))
            .header("X-Tenant-Id", TENANT_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":{\"message_id\":\"tg-localdev-1\",\"chat\":{\"id\":\"cust-1\"},\"text\":\"Need filters\"}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verificationStatus").value("SKIPPED_LOCAL_DEV"))
        .andReturn().getResponse().getContentAsString();

    // Honest classification: a local-dev skip is never reported as a verified signature.
    assertThat(body).doesNotContain("CONFIGURED_VERIFY_ONLY").doesNotContain("\"VERIFIED\"");
    verify(eventRepository).save(any(InboundChannelEvent.class));
    assertNoSensitiveLeak(body);
  }
}
