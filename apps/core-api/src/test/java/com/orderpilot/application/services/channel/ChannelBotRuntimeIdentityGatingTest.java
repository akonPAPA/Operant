package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.orderpilot.domain.channel.ChannelIdentity;
import com.orderpilot.domain.channel.ChannelProviderType;
import com.orderpilot.domain.channel.InboundChannelEvent;
import com.orderpilot.domain.channel.InboundChannelEventRepository;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
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
 * OP-CAP-06C proof that identity-aware context gates the bridge safely: unknown senders cannot get
 * price/status answers, blocked senders never reach the runtime even for otherwise-allowed flows,
 * a resolved sender reaches the controlled runtime path, and unmapped RFQ stays backward-compatible.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    ChannelBotRuntimeBridgeService.class,
    ChannelRfqHandoffService.class,
    BotRuntimeConfigurationService.class,
    BotRuntimePolicyService.class,
    ChannelIdentityResolverService.class,
    ChannelIdentityService.class,
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
    IntakeValidationService.class,
    ProcessingJobService.class,
    AuditEventService.class,
    JsonSupport.class,
    ObjectMapper.class,
    CoreConfiguration.class
})
class ChannelBotRuntimeIdentityGatingTest {
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  @Autowired private ChannelBotRuntimeBridgeService bridgeService;
  @Autowired private ChannelConnectionService connectionService;
  @Autowired private ChannelIdentityService identityService;
  @Autowired private BotConversationRepository conversationRepository;
  @Autowired private InboundChannelEventRepository eventRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private TenantRepository tenantRepository;
  @Autowired private CustomerAccountRepository customerAccountRepository;

  @AfterEach void clearTenant() { TenantContext.clear(); }

  @Test void unknownCustomerPriceFlowIsBlockedAndDrivesNoRuntime() throws Exception {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    ChannelConnection connection = activeTelegramConnection();

    ChannelBotBridgeResultResponse result = bridgeService.handleInbound(
        connection.getId(), ChannelProviderType.TELEGRAM, pricePayload("10", "m1", "chat-unknown"), headers());

    assertThat(result.bridgeStatus()).isEqualTo("BLOCKED_BY_CONFIG");
    assertThat(conversationRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId)).isEmpty();
    assertThat(auditActions(tenantId)).contains("BOT_CHANNEL_EVENT_IDENTITY_RESOLVED");
  }

  @Test void resolvedCustomerPriceFlowReachesRuntime() throws Exception {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    ChannelConnection connection = activeTelegramConnection();
    ChannelIdentity identity = identityService.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "chat-known", null, null, "User");
    UUID accountId = seedCustomerAccount(tenantId);
    identityService.linkIdentity(identity.getId(), accountId, null, UUID.randomUUID(), "operator linked");

    ChannelBotBridgeResultResponse result = bridgeService.handleInbound(
        connection.getId(), ChannelProviderType.TELEGRAM, pricePayload("20", "m2", "chat-known"), headers());

    // Resolved identity lets the price flow reach the controlled runtime (which still routes to review).
    assertThat(result.bridgeStatus()).isNotEqualTo("BLOCKED_BY_CONFIG");
    assertThat(result.bridgeStatus()).isNotEqualTo("IDENTITY_BLOCKED");
    assertThat(conversationRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId)).hasSize(1);
    assertThat(auditActions(tenantId)).contains("BOT_CHANNEL_EVENT_IDENTITY_RESOLVED");
  }

  @Test void blockedIdentityDrivesNoRuntimeEvenForOtherwiseAllowedRfq() throws Exception {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    ChannelConnection connection = activeTelegramConnection();
    ChannelIdentity identity = identityService.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "chat-blocked", null, null, "User");
    identityService.blockIdentity(identity.getId(), "abuse");

    ChannelBotBridgeResultResponse result = bridgeService.handleInbound(
        connection.getId(), ChannelProviderType.TELEGRAM, rfqPayload("30", "m3", "chat-blocked"), headers());

    assertThat(result.bridgeStatus()).isEqualTo("IDENTITY_BLOCKED");
    assertThat(result.botConversationId()).isNull();
    assertThat(conversationRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId)).isEmpty();
    assertThat(auditActions(tenantId)).contains("BOT_CHANNEL_EVENT_IDENTITY_BLOCKED");
  }

  @Test void ambiguousIdentityPriceFlowRoutesToReviewAndIsNotTreatedAsLinked() throws Exception {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    ChannelConnection connection = activeTelegramConnection();
    // Operator flagged the sender for manual review: resolver sees AMBIGUOUS, not RESOLVED.
    ChannelIdentity identity = identityService.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "chat-ambiguous", null, null, "User");
    identityService.markNeedsReview(identity.getId(), "operator flagged");

    ChannelBotBridgeResultResponse result = bridgeService.handleInbound(
        connection.getId(), ChannelProviderType.TELEGRAM, pricePayload("50", "m5", "chat-ambiguous"), headers());

    // An unconfirmed candidate must route to operator review — never be treated as a trusted linked
    // customer and never reach the controlled price runtime.
    assertThat(result.bridgeStatus()).isEqualTo("BLOCKED_BY_CONFIG");
    assertThat(result.requiresHumanReview()).isTrue();
    assertThat(result.botConversationId()).isNull();
    assertThat(conversationRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId)).isEmpty();
    // Proof it was the ambiguous handoff path, not a generic config block or a resolved customer.
    InboundChannelEvent event = eventRepository.findById(result.eventId()).orElseThrow();
    assertThat(event.getBotRuntimeStatus()).isEqualTo("CONFIG_BLOCKED:AMBIGUOUS_CUSTOMER_HANDOFF");
    assertThat(auditActions(tenantId)).contains("BOT_CHANNEL_EVENT_IDENTITY_RESOLVED");
    assertThat(auditActions(tenantId)).contains("BOT_CHANNEL_EVENT_BLOCKED_BY_CONFIG");
  }

  @Test void unmappedRfqRemainsBackwardCompatible() throws Exception {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    ChannelConnection connection = activeTelegramConnection();

    ChannelBotBridgeResultResponse result = bridgeService.handleInbound(
        connection.getId(), ChannelProviderType.TELEGRAM, rfqPayload("40", "m4", "chat-rfq"), headers());

    assertThat(result.bridgeStatus()).isEqualTo("RFQ_DRAFT_REQUIRES_HUMAN_REVIEW");
    assertThat(result.botConversationId()).isNotNull();
    assertThat(conversationRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId)).hasSize(1);
  }

  // --- helpers ---

  private UUID seedTenant() {
    return tenantRepository.save(new Tenant("idg-" + UUID.randomUUID(), "Identity Gating Test", "ACTIVE", Instant.parse("2026-06-04T00:00:00Z"))).getId();
  }

  private UUID seedCustomerAccount(UUID tenantId) {
    CustomerAccount account = new CustomerAccount(
        tenantId, null, "ACC-" + UUID.randomUUID().toString().substring(0, 8),
        "Test Customer", null, null, "ACTIVE", "USD", null, Instant.parse("2026-06-05T00:00:00Z"));
    return customerAccountRepository.save(account).getId();
  }

  private ChannelConnection activeTelegramConnection() {
    ChannelConnection draft = connectionService.createDraft(ChannelProviderType.TELEGRAM, "Telegram", null, null, null);
    return connectionService.activate(draft.getId());
  }

  private JsonNode pricePayload(String updateId, String messageId, String chatId) throws Exception {
    return objectMapper.readTree("{\"update_id\":\"" + updateId + "\",\"message\":{\"message_id\":\"" + messageId
        + "\",\"chat\":{\"id\":\"" + chatId + "\"},\"text\":\"what is the price of BRK-100\"}}");
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
