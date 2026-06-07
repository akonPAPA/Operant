package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.ChannelBotBridgeDtos.ChannelBotBridgeEventResponse;
import com.orderpilot.api.dto.ChannelBotBridgeDtos.ChannelBotBridgeResultResponse;
import com.orderpilot.api.dto.ChannelBotBridgeDtos.ChannelBotBridgeStatusResponse;
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
    ChannelIdentityResolverService.class,
    ChannelRfqHandoffService.class,
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
  @Autowired private com.orderpilot.domain.channel.ChannelRfqHandoffRepository rfqHandoffRepository;
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

  @Test void listRecentBridgedEventsAppliesRequestedLimitWithinTenant() throws Exception {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    ChannelConnection connection = activeTelegramConnection(null);
    bridgeService.handleInbound(connection.getId(), ChannelProviderType.TELEGRAM, rfqPayload("1001", "rm1", "rchat-1"), headers());
    bridgeService.handleInbound(connection.getId(), ChannelProviderType.TELEGRAM, rfqPayload("1002", "rm2", "rchat-2"), headers());
    bridgeService.handleInbound(connection.getId(), ChannelProviderType.TELEGRAM, rfqPayload("1003", "rm3", "rchat-3"), headers());

    assertThat(eventRepository.findByTenantIdOrderByReceivedAtDesc(tenantId)).hasSize(3);
    // A smaller requested limit is honored.
    assertThat(bridgeService.listRecentBridgedEvents(2)).hasSize(2);
    // No limit falls back to the safe default window (>= the 3 seeded events).
    assertThat(bridgeService.listRecentBridgedEvents(null)).hasSize(3);
  }

  @Test void recentBridgedEventsAreTenantScoped() throws Exception {
    UUID tenantA = seedTenant();
    TenantContext.setTenantId(tenantA);
    ChannelConnection connectionA = activeTelegramConnection(null);
    bridgeService.handleInbound(connectionA.getId(), ChannelProviderType.TELEGRAM, rfqPayload("2001", "tm1", "tchat-1"), headers());
    assertThat(bridgeService.listRecentBridgedEvents(null)).hasSize(1);

    UUID tenantB = seedTenant();
    TenantContext.setTenantId(tenantB);
    // Tenant B has its own (empty) recent window and cannot see tenant A's bridged events.
    List<ChannelBotBridgeEventResponse> tenantBEvents = bridgeService.listRecentBridgedEvents(null);
    assertThat(tenantBEvents).isEmpty();
    assertThat(bridgeService.getBridgeStatus(null).recentEventCount()).isZero();
  }

  @Test void bridgeStatusClampsLimitAndSummarizesRecentWindowSafely() throws Exception {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    ChannelConnection connection = activeTelegramConnection(null);
    bridgeService.handleInbound(connection.getId(), ChannelProviderType.TELEGRAM, rfqPayload("3001", "sm1", "schat-1"), headers());

    // An oversized client limit is clamped to the server-side hard cap.
    ChannelBotBridgeStatusResponse clamped = bridgeService.getBridgeStatus(1_000_000);
    assertThat(clamped.recentWindowLimit()).isEqualTo(ChannelEventNormalizationService.MAX_EVENT_LIMIT);
    // A null limit uses the safe default window.
    assertThat(bridgeService.getBridgeStatus(null).recentWindowLimit()).isEqualTo(ChannelEventNormalizationService.DEFAULT_EVENT_LIMIT);

    ChannelBotBridgeStatusResponse status = bridgeService.getBridgeStatus(null);
    assertThat(status.externalExecution()).isEqualTo("DISABLED");
    assertThat(status.recentEventCount()).isEqualTo(1);
    assertThat(status.bridgedToBotCount()).isEqualTo(1);
    assertThat(status.pendingOrUnbridgedCount()).isZero();
    assertThat(status.supportedFlows()).contains("CREATE_REVIEWABLE_RFQ_DRAFT", "HUMAN_HANDOFF_REVIEW");
    // The bridge can never approve or write to trusted/external systems.
    assertThat(status.forbiddenActions())
        .contains("QUOTE_APPROVAL", "ORDER_APPROVAL", "EXTERNAL_ERP_OR_1C_WRITE", "CROSS_TENANT_SEARCH");
  }

  @Test void rfqTelegramMessageProducesReviewableHandoffAndDuplicateDoesNotDuplicateIt() throws Exception {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    ChannelConnection connection = activeTelegramConnection(null);
    JsonNode payload = rfqPayload("8001", "rfq-hm1", "rfq-chat-1");

    ChannelBotBridgeResultResponse first = bridgeService.handleInbound(connection.getId(), ChannelProviderType.TELEGRAM, payload, headers());

    // A reviewable RFQ handoff is created from the verified channel message.
    var handoffs = rfqHandoffRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    assertThat(handoffs).hasSize(1);
    assertThat(handoffs.get(0).getStatus())
        .isEqualTo(com.orderpilot.domain.channel.ChannelRfqHandoffStatus.PENDING_REVIEW);
    assertThat(handoffs.get(0).getInboundChannelEventId()).isEqualTo(first.eventId());
    assertThat(handoffs.get(0).getSourceChannel()).isEqualTo("TELEGRAM");
    assertThat(auditActions(tenantId)).contains("CHANNEL_RFQ_HANDOFF_CREATED");

    // Re-delivery of the identical provider payload must not create a duplicate handoff.
    bridgeService.handleInbound(connection.getId(), ChannelProviderType.TELEGRAM, payload, headers());
    assertThat(rfqHandoffRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).hasSize(1);
  }

  @Test void rfqHandoffIsReviewableDraftOnlyAndBotApprovesNothing() throws Exception {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    ChannelConnection connection = activeTelegramConnection(null);

    ChannelBotBridgeResultResponse result = bridgeService.handleInbound(
        connection.getId(), ChannelProviderType.TELEGRAM, rfqPayload("8002", "rfq-hm2", "rfq-chat-2"), headers());

    // Bot path remains safe: external execution disabled, request routed for human review only.
    assertThat(result.externalExecution()).isEqualTo("DISABLED");
    assertThat(result.requiresHumanReview()).isTrue();
    // The handoff is a PENDING_REVIEW draft request, never an approved quote/order.
    var handoffs = rfqHandoffRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    assertThat(handoffs).hasSize(1);
    assertThat(handoffs.get(0).getStatus())
        .isEqualTo(com.orderpilot.domain.channel.ChannelRfqHandoffStatus.PENDING_REVIEW);
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
