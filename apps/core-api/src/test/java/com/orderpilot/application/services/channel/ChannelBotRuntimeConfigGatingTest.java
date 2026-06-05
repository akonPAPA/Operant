package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.BotRuntimeConfigurationDtos.BotRuntimeConfigurationUpdateRequest;
import com.orderpilot.api.dto.ChannelBotBridgeDtos.ChannelBotBridgeResultResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.IntakeValidationService;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.application.services.ProcessingJobService;
import com.orderpilot.application.services.bot.BotPolicyService;
import com.orderpilot.application.services.bot.BotResponseDraftService;
import com.orderpilot.application.services.bot.BotReviewHandoffService;
import com.orderpilot.application.services.bot.BotRuntimePolicyService;
import com.orderpilot.application.services.bot.BotRuntimeService;
import com.orderpilot.application.services.bot.BotWebhookSecurityService;
import com.orderpilot.application.services.bot.NoopTelegramOutboundTransport;
import com.orderpilot.application.services.bot.RuleBasedBotIntentClassifier;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEvent;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.bot.BotConversationRepository;
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

/**
 * OP-CAP-06B proof that controlled bot runtime configuration actually gates runtime behavior:
 * a disabled flow never reaches {@code BotRuntimeService}, so no bot conversation / RFQ / draft
 * is produced for it, and configuration is strictly tenant-scoped.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    ChannelBotRuntimeBridgeService.class,
    BotRuntimeConfigurationService.class,
    BotRuntimePolicyService.class,
    ChannelIdentityResolverService.class,
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
class ChannelBotRuntimeConfigGatingTest {
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  @Autowired private ChannelBotRuntimeBridgeService bridgeService;
  @Autowired private BotRuntimeConfigurationService configurationService;
  @Autowired private ChannelConnectionService connectionService;
  @Autowired private BotConversationRepository conversationRepository;
  @Autowired private InboundChannelEventRepository eventRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private TenantRepository tenantRepository;

  @AfterEach void clearTenant() { TenantContext.clear(); }

  @Test void disabledRfqFlowBlocksRuntimeAndCreatesNoDraft() throws Exception {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    ChannelConnection connection = activeTelegramConnection();
    disableRfq(connection.getId());

    ChannelBotBridgeResultResponse result = bridgeService.handleInbound(
        connection.getId(), ChannelProviderType.TELEGRAM, rfqPayload("10", "m1", "chat-1"), headers());

    assertThat(result.bridgeStatus()).isEqualTo("BLOCKED_BY_CONFIG");
    assertThat(result.botConversationId()).isNull();
    assertThat(result.requiresHumanReview()).isTrue();
    assertThat(result.externalExecution()).isEqualTo("DISABLED");
    // No controlled bot workflow was created: the runtime was never invoked.
    assertThat(conversationRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId)).isEmpty();
    InboundChannelEvent event = eventRepository.findById(result.eventId()).orElseThrow();
    assertThat(event.getBotRuntimeStatus()).startsWith("CONFIG_BLOCKED");
    assertThat(event.getBotConversationId()).isNull();
    assertThat(auditActions(tenantId)).contains("BOT_CHANNEL_EVENT_BLOCKED_BY_CONFIG");
  }

  @Test void disabledPriceFlowProducesNoPriceOutput() throws Exception {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    ChannelConnection connection = activeTelegramConnection();
    // Disable price entirely; visibility must be NEVER for a valid combination.
    configurationService.updateForConnection(connection.getId(),
        new BotRuntimeConfigurationUpdateRequest(null, null, null, "DISABLED", null, null, null, null, null, null, null,
            null, "NEVER", null, null, null));

    JsonNode pricePayload = objectMapper.readTree(
        "{\"update_id\":\"20\",\"message\":{\"message_id\":\"m2\",\"chat\":{\"id\":\"chat-2\"},\"text\":\"what is the price of BRK-100\"}}");
    ChannelBotBridgeResultResponse result = bridgeService.handleInbound(
        connection.getId(), ChannelProviderType.TELEGRAM, pricePayload, headers());

    assertThat(result.bridgeStatus()).isEqualTo("BLOCKED_BY_CONFIG");
    assertThat(result.detectedIntent()).isEqualTo("PRICE");
    assertThat(conversationRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId)).isEmpty();
    assertThat(auditActions(tenantId)).contains("BOT_CHANNEL_EVENT_BLOCKED_BY_CONFIG");
  }

  @Test void tenantConfigurationDoesNotAffectAnotherTenantRuntime() throws Exception {
    // Tenant A disables RFQ.
    UUID tenantA = seedTenant();
    TenantContext.setTenantId(tenantA);
    ChannelConnection connectionA = activeTelegramConnection();
    disableRfq(connectionA.getId());
    ChannelBotBridgeResultResponse blockedForA = bridgeService.handleInbound(
        connectionA.getId(), ChannelProviderType.TELEGRAM, rfqPayload("30", "m3", "chat-3"), headers());
    assertThat(blockedForA.bridgeStatus()).isEqualTo("BLOCKED_BY_CONFIG");
    assertThat(conversationRepository.findByTenantIdOrderByUpdatedAtDesc(tenantA)).isEmpty();

    // Tenant B keeps the safe default (RFQ allowed as review-only) and is unaffected.
    UUID tenantB = seedTenant();
    TenantContext.setTenantId(tenantB);
    ChannelConnection connectionB = activeTelegramConnection();
    ChannelBotBridgeResultResponse bridgedForB = bridgeService.handleInbound(
        connectionB.getId(), ChannelProviderType.TELEGRAM, rfqPayload("40", "m4", "chat-4"), headers());
    assertThat(bridgedForB.bridgeStatus()).isEqualTo("RFQ_DRAFT_REQUIRES_HUMAN_REVIEW");
    assertThat(bridgedForB.botConversationId()).isNotNull();
    assertThat(conversationRepository.findByTenantIdOrderByUpdatedAtDesc(tenantB)).hasSize(1);
  }

  // --- helpers ---

  private void disableRfq(UUID connectionId) {
    configurationService.updateForConnection(connectionId,
        new BotRuntimeConfigurationUpdateRequest(null, null, null, null, "DISABLED", null, null, null, null, null, null,
            null, null, null, null, null));
  }

  private UUID seedTenant() {
    return tenantRepository.save(new Tenant("gate-" + UUID.randomUUID(), "Gating Test", "ACTIVE", Instant.parse("2026-06-04T00:00:00Z"))).getId();
  }

  private ChannelConnection activeTelegramConnection() {
    ChannelConnection draft = connectionService.createDraft(ChannelProviderType.TELEGRAM, "Telegram", null, null, null);
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
