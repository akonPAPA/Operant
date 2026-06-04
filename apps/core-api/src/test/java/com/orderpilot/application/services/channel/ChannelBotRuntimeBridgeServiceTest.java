package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.ChannelBotBridgeDtos.ChannelBotBridgeResultResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.IntakeValidationService;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.application.services.ProcessingJobService;
import com.orderpilot.application.services.bot.BotPolicyService;
import com.orderpilot.application.services.bot.BotResponseDraftService;
import com.orderpilot.application.services.bot.BotReviewHandoffService;
import com.orderpilot.application.services.bot.BotRuntimeService;
import com.orderpilot.application.services.bot.BotWebhookSecurityService;
import com.orderpilot.application.services.bot.NoopTelegramOutboundTransport;
import com.orderpilot.application.services.bot.RuleBasedBotIntentClassifier;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEvent;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.bot.BotConversationRepository;
import com.orderpilot.domain.bot.BotHandoffRepository;
import com.orderpilot.domain.bot.BotMessageRepository;
import com.orderpilot.domain.bot.BotRfqRequestRepository;
import com.orderpilot.domain.channel.ChannelConnection;
import com.orderpilot.domain.channel.ChannelProviderType;
import com.orderpilot.domain.channel.InboundChannelEvent;
import com.orderpilot.domain.channel.InboundChannelEventRepository;
import com.orderpilot.domain.tenant.Tenant;
import com.orderpilot.domain.tenant.TenantRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    ChannelBotRuntimeBridgeService.class,
    BotRuntimeConfigurationService.class,
    com.orderpilot.application.services.bot.BotRuntimePolicyService.class,
    ChannelEventNormalizationService.class,
    ChannelConnectionService.class,
    TelegramChannelAdapter.class,
    BotRuntimeService.class,
    BotResponseDraftService.class,
    BotReviewHandoffService.class,
    NoopTelegramOutboundTransport.class,
    RuleBasedBotIntentClassifier.class,
    BotPolicyService.class,
    BotWebhookSecurityService.class,
    ChannelGatewayService.class,
    ChannelIdentityService.class,
    IntakeValidationService.class,
    ProcessingJobService.class,
    AuditEventService.class,
    JsonSupport.class,
    ObjectMapper.class,
    CoreConfiguration.class
})
class ChannelBotRuntimeBridgeServiceTest {
  private static final String SECRET_REF = "vault://telegram/UNIT-TEST-TOKEN-REF";

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  @Autowired private ChannelBotRuntimeBridgeService bridgeService;
  @Autowired private ChannelConnectionService connectionService;
  @Autowired private InboundChannelEventRepository eventRepository;
  @Autowired private BotConversationRepository conversationRepository;
  @Autowired private BotMessageRepository messageRepository;
  @Autowired private BotRfqRequestRepository rfqRequestRepository;
  @Autowired private BotHandoffRepository handoffRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private TenantRepository tenantRepository;

  @AfterEach void clearTenant() { TenantContext.clear(); }

  @Test void bridgesVerifiedTelegramEventIntoControlledBotRuntime() throws Exception {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    ChannelConnection connection = activeTelegramConnection(null);

    ChannelBotBridgeResultResponse result = bridgeService.handleInbound(
        connection.getId(), ChannelProviderType.TELEGRAM, rfqPayload("100", "m1", "chat-1"), headers());

    assertThat(result.bridgeStatus()).isEqualTo("RFQ_DRAFT_REQUIRES_HUMAN_REVIEW");
    assertThat(result.botConversationId()).isNotNull();
    assertThat(result.botMessageId()).isNotNull();
    assertThat(result.requiresHumanReview()).isTrue();
    assertThat(result.createdRfqDraftId()).isNotNull();
    assertThat(result.externalExecution()).isEqualTo("DISABLED");

    InboundChannelEvent persisted = eventRepository.findById(result.eventId()).orElseThrow();
    assertThat(persisted.getStatus()).isEqualTo("ROUTED");
    assertThat(persisted.getBotConversationId()).isEqualTo(result.botConversationId());

    assertThat(auditActions(tenantId)).contains("BOT_CHANNEL_EVENT_BRIDGED");
  }

  @Test void duplicateProviderDeliveryDoesNotDuplicateBotWorkflow() throws Exception {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    ChannelConnection connection = activeTelegramConnection(null);
    JsonNode payload = rfqPayload("200", "m-dup", "chat-2");

    ChannelBotBridgeResultResponse first = bridgeService.handleInbound(connection.getId(), ChannelProviderType.TELEGRAM, payload, headers());
    assertThat(first.bridgeStatus()).isEqualTo("RFQ_DRAFT_REQUIRES_HUMAN_REVIEW");

    // Snapshot all controlled side effects created by the first delivery.
    int conversations = conversationRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId).size();
    int messages = messageRepository.findByTenantIdAndConversationIdOrderByCreatedAtAsc(tenantId, first.botConversationId()).size();
    int rfqRequests = rfqRequestRepository.findByTenantIdAndConversationIdOrderByCreatedAtDesc(tenantId, first.botConversationId()).size();
    int handoffs = handoffRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).size();
    int events = eventRepository.findByTenantIdOrderByReceivedAtDesc(tenantId).size();

    // Re-deliver the identical provider payload.
    ChannelBotBridgeResultResponse second = bridgeService.handleInbound(connection.getId(), ChannelProviderType.TELEGRAM, payload, headers());
    assertThat(second.bridgeStatus()).isEqualTo("DUPLICATE_IGNORED");
    assertThat(second.botConversationId()).isEqualTo(first.botConversationId());

    // Replay must not create any duplicate controlled side effects.
    assertThat(conversations).isEqualTo(1);
    assertThat(messages).isEqualTo(1);
    assertThat(rfqRequests).isEqualTo(1);
    assertThat(events).isEqualTo(1);
    assertThat(conversationRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId)).hasSize(conversations);
    assertThat(messageRepository.findByTenantIdAndConversationIdOrderByCreatedAtAsc(tenantId, first.botConversationId())).hasSize(messages);
    assertThat(rfqRequestRepository.findByTenantIdAndConversationIdOrderByCreatedAtDesc(tenantId, first.botConversationId())).hasSize(rfqRequests);
    assertThat(handoffRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).hasSize(handoffs);
    assertThat(eventRepository.findByTenantIdOrderByReceivedAtDesc(tenantId)).hasSize(events);
  }

  @Test void disabledConnectionIsRejectedAndDrivesNoBotWorkflow() throws Exception {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    ChannelConnection connection = activeTelegramConnection(null);
    connectionService.disable(connection.getId());

    assertThatThrownBy(() -> bridgeService.handleInbound(connection.getId(), ChannelProviderType.TELEGRAM, rfqPayload("300", "m3", "chat-3"), headers()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(conversationRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId)).isEmpty();
  }

  @Test void unknownConnectionIsRejected() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    assertThatThrownBy(() -> bridgeService.handleInbound(UUID.randomUUID(), ChannelProviderType.TELEGRAM, rfqPayload("400", "m4", "chat-4"), headers()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test void tenantCannotBridgeAnotherTenantsConnection() throws Exception {
    UUID tenantA = seedTenant();
    TenantContext.setTenantId(tenantA);
    ChannelConnection connection = activeTelegramConnection(null);

    UUID tenantB = seedTenant();
    TenantContext.setTenantId(tenantB);
    assertThatThrownBy(() -> bridgeService.handleInbound(connection.getId(), ChannelProviderType.TELEGRAM, rfqPayload("500", "m5", "chat-5"), headers()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test void malformedTelegramPayloadRoutesToReviewWithoutUncontrolledFailure() throws Exception {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    ChannelConnection connection = activeTelegramConnection(null);
    JsonNode malformed = objectMapper.readTree("{\"message\":{\"message_id\":\"m6\",\"chat\":{\"id\":\"chat-6\"}}}");

    ChannelBotBridgeResultResponse result = bridgeService.handleInbound(connection.getId(), ChannelProviderType.TELEGRAM, malformed, headers());

    assertThat(result.bridgeStatus()).isEqualTo("BRIDGE_REJECTED");
    InboundChannelEvent persisted = eventRepository.findById(result.eventId()).orElseThrow();
    assertThat(persisted.getStatus()).isEqualTo("FAILED");
    assertThat(persisted.getBotRuntimeStatus()).isEqualTo("BRIDGE_FAILED");
    assertThat(conversationRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId)).isEmpty();
  }

  @Test void secretReferenceIsNeverExposedInResponseOrAudit() throws Exception {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    ChannelConnection connection = activeTelegramConnection(SECRET_REF);

    ChannelBotBridgeResultResponse result = bridgeService.handleInbound(
        connection.getId(), ChannelProviderType.TELEGRAM, rfqPayload("600", "m7", "chat-7"), headers());

    String serialized = objectMapper.writeValueAsString(result);
    assertThat(serialized).doesNotContain("vault://").doesNotContain("UNIT-TEST-TOKEN-REF");

    for (AuditEvent event : auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId)) {
      assertThat(event.getMetadata() == null ? "" : event.getMetadata()).doesNotContain("UNIT-TEST-TOKEN-REF").doesNotContain("vault://");
    }
  }

  @Test void externalExecutionRemainsDisabledAndBotCreatesNoFinalQuote() throws Exception {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    ChannelConnection connection = activeTelegramConnection(null);

    ChannelBotBridgeResultResponse result = bridgeService.handleInbound(
        connection.getId(), ChannelProviderType.TELEGRAM, rfqPayload("700", "m8", "chat-8"), headers());

    assertThat(result.externalExecution()).isEqualTo("DISABLED");
    String bridgedMetadata = auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId).stream()
        .filter(e -> "BOT_CHANNEL_EVENT_BRIDGED".equals(e.getAction()))
        .map(AuditEvent::getMetadata)
        .findFirst()
        .orElse("");
    assertThat(bridgedMetadata).contains("\"externalExecution\":\"DISABLED\"");
    // The bridge captures a reviewable RFQ request only; no final quote/order is approved by the bot.
    assertThat(result.requiresHumanReview()).isTrue();
    assertThat(result.createdRfqDraftId()).isNotNull();
  }

  // --- helpers ---

  private UUID seedTenant() {
    return tenantRepository.save(new Tenant("bridge-" + UUID.randomUUID(), "Bridge Test", "ACTIVE", Instant.parse("2026-06-04T00:00:00Z"))).getId();
  }

  private ChannelConnection activeTelegramConnection(String secretRef) {
    ChannelConnection draft = connectionService.createDraft(ChannelProviderType.TELEGRAM, "Telegram", null, null, secretRef);
    return connectionService.activate(draft.getId());
  }

  private JsonNode rfqPayload(String updateId, String messageId, String chatId) throws Exception {
    return objectMapper.readTree("{\"update_id\":\"" + updateId + "\",\"message\":{\"message_id\":\"" + messageId
        + "\",\"chat\":{\"id\":\"" + chatId + "\"},\"text\":\"Please quote 10 of BRK-100\"}}");
  }

  private java.util.Map<String, String> headers() {
    return java.util.Map.of();
  }

  private List<String> auditActions(UUID tenantId) {
    return auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId).stream().map(AuditEvent::getAction).toList();
  }
}
