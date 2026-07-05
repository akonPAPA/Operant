package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEvent;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.bot.BotConversationRepository;
import com.orderpilot.domain.bot.BotMessageRepository;
import com.orderpilot.domain.bot.BotRfqRequestRepository;
import com.orderpilot.domain.channel.ChannelConnection;
import com.orderpilot.domain.channel.ChannelProviderType;
import com.orderpilot.domain.channel.ChannelRfqHandoffRepository;
import com.orderpilot.domain.channel.InboundChannelEventRepository;
import com.orderpilot.domain.integration.ChangeRequestRepository;
import com.orderpilot.domain.integration.ConnectorCommandRepository;
import com.orderpilot.domain.integration.ConnectorSandboxExecutionRepository;
import com.orderpilot.domain.integration.OutboxEventRepository;
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
  LocalDemoRfqIntakeService.class,
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
  com.orderpilot.application.services.runtime.RuntimeGuardService.class,
  com.orderpilot.application.services.runtime.QuotaGuard.class,
  com.orderpilot.application.services.runtime.RateLimitService.class,
  com.orderpilot.application.services.runtime.FeatureEntitlementGuard.class,
  com.orderpilot.application.services.runtime.UsageMeterService.class,
  JsonSupport.class,
  ObjectMapper.class,
  CoreConfiguration.class
})
class LocalDemoRfqIntakeServiceTest {
  @Autowired private LocalDemoRfqIntakeService intakeService;
  @Autowired private ChannelConnectionService connectionService;
  @Autowired private InboundChannelEventRepository eventRepository;
  @Autowired private ChannelRfqHandoffRepository rfqHandoffRepository;
  @Autowired private BotConversationRepository conversationRepository;
  @Autowired private BotMessageRepository messageRepository;
  @Autowired private BotRfqRequestRepository botRfqRequestRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private ConnectorCommandRepository connectorCommandRepository;
  @Autowired private ConnectorSandboxExecutionRepository sandboxExecutionRepository;
  @Autowired private ChangeRequestRepository changeRequestRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private TenantRepository tenantRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void validCallCreatesExactlyOnePendingHandoffAndReplayCreatesNothingElse() {
    UUID tenantId = seedTenant("local-demo");
    TenantContext.setTenantId(tenantId);
    activeDemoConnection();

    var first =
        intakeService.createOrGet(
            UUID.fromString("00000000-0000-4000-8000-000000000002"));
    var replay =
        intakeService.createOrGet(
            UUID.fromString("00000000-0000-4000-8000-000000000002"));

    assertThat(first.handoffId()).isEqualTo(replay.handoffId());
    assertThat(first.status()).isEqualTo("PENDING_REVIEW");
    assertThat(replay.status()).isEqualTo("PENDING_REVIEW");
    assertThat(eventRepository.findByTenantIdOrderByReceivedAtDesc(tenantId))
        .hasSize(1);
    assertThat(rfqHandoffRepository.findByTenantIdOrderByCreatedAtDesc(tenantId))
        .hasSize(1);
    assertThat(conversationRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId))
        .hasSize(1);
    assertThat(messageRepository.findAll()).hasSize(1);
    assertThat(botRfqRequestRepository.findAll()).hasSize(1);
    assertThat(auditActions(tenantId))
        .contains(
            "CHANNEL_WEBHOOK_ACCEPTED",
            "CHANNEL_RFQ_HANDOFF_CREATED",
            "BOT_CHANNEL_EVENT_BRIDGED",
            "BOT_CHANNEL_EVENT_DUPLICATE_IGNORED",
            "LOCAL_DEMO_RFQ_INTAKE_READY");
    assertNoExternalWriteState();
  }

  @Test
  void crossTenantCallCannotResolveSourceAndDoesNotMutateEitherTenant() {
    UUID tenantA = seedTenant("local-demo-a");
    UUID tenantB = seedTenant("local-demo-b");
    TenantContext.setTenantId(tenantA);
    activeDemoConnection();
    var created = intakeService.createOrGet(UUID.randomUUID());

    TenantContext.setTenantId(tenantB);
    assertThatThrownBy(() -> intakeService.createOrGet(UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("not configured for this tenant");

    assertThat(eventRepository.findByTenantIdOrderByReceivedAtDesc(tenantB))
        .isEmpty();
    assertThat(rfqHandoffRepository.findByTenantIdOrderByCreatedAtDesc(tenantB))
        .isEmpty();
    assertThat(rfqHandoffRepository.findByIdAndTenantId(created.handoffId(), tenantB))
        .isEmpty();
    assertThat(rfqHandoffRepository.findByTenantIdOrderByCreatedAtDesc(tenantA))
        .hasSize(1);
    assertNoExternalWriteState();
  }

  private UUID seedTenant(String prefix) {
    return tenantRepository
        .save(
            new Tenant(
                prefix + "-" + UUID.randomUUID(),
                "Local Demo Test",
                "ACTIVE",
                Instant.parse("2026-07-04T00:00:00Z")))
        .getId();
  }

  private ChannelConnection activeDemoConnection() {
    ChannelConnection draft =
        connectionService.createDraft(
            ChannelProviderType.TELEGRAM,
            "Deterministic local demo RFQ source",
            LocalDemoRfqIntakeService.DEMO_EXTERNAL_ACCOUNT_ID,
            null,
            null);
    return connectionService.activate(draft.getId());
  }

  private List<String> auditActions(UUID tenantId) {
    return auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId)
        .stream()
        .map(AuditEvent::getAction)
        .toList();
  }

  private void assertNoExternalWriteState() {
    assertThat(connectorCommandRepository.count()).isZero();
    assertThat(sandboxExecutionRepository.count()).isZero();
    assertThat(changeRequestRepository.count()).isZero();
    assertThat(outboxEventRepository.count()).isZero();
  }
}
