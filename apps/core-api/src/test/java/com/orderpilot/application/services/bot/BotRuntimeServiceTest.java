package com.orderpilot.application.services.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.Stage7Dtos.BotWebhookAckResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.IntakeValidationService;
import com.orderpilot.application.services.ProcessingJobService;
import com.orderpilot.application.services.channel.ChannelGatewayService;
import com.orderpilot.application.services.channel.ChannelIdentityService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.bot.*;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.integration.ChangeRequestRepository;
import com.orderpilot.domain.intake.ChannelMessageRepository;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({BotRuntimeService.class, RuleBasedBotIntentClassifier.class, BotWebhookSecurityService.class, ChannelGatewayService.class, ChannelIdentityService.class, IntakeValidationService.class, ProcessingJobService.class, AuditEventService.class, CoreConfiguration.class})
class BotRuntimeServiceTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  @Autowired private BotRuntimeService service;
  @Autowired private BotConversationRepository conversationRepository;
  @Autowired private BotMessageRepository messageRepository;
  @Autowired private BotRfqRequestRepository rfqRequestRepository;
  @Autowired private BotHandoffRepository handoffRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private ChannelMessageRepository channelMessageRepository;
  @Autowired private DraftQuoteRepository draftQuoteRepository;
  @Autowired private DraftOrderRepository draftOrderRepository;
  @Autowired private InventorySnapshotRepository inventorySnapshotRepository;
  @Autowired private PriceRuleRepository priceRuleRepository;
  @Autowired private CustomerAccountRepository customerAccountRepository;
  @Autowired private ChangeRequestRepository changeRequestRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void validTelegramRfqMessageCreatesConversationMessageRfqDraftGatewayMessageAndAuditEvents() throws Exception {
    TenantContext.setTenantId(UUID.randomUUID());

    BotWebhookAckResponse response = service.handleTelegramUpdate(objectMapper.readTree("""
        {"update_id":1001,"message":{"message_id":501,"chat":{"id":90001},"text":"Need quote for brake pads RFQ"}}
        """));

    assertThat(response.intent()).isEqualTo(BotIntent.RFQ_REQUEST);
    assertThat(response.status()).isEqualTo("RFQ_DRAFT_REQUIRES_HUMAN_REVIEW");
    assertThat(response.requiresHumanReview()).isTrue();
    assertThat(response.createdRfqDraftId()).isNotNull();
    assertThat(conversationRepository.count()).isEqualTo(1);
    assertThat(messageRepository.count()).isEqualTo(1);
    assertThat(channelMessageRepository.count()).isEqualTo(1);
    assertThat(channelMessageRepository.findAll().get(0).getChannel()).isEqualTo("TELEGRAM");
    assertThat(rfqRequestRepository.count()).isEqualTo(1);
    assertThat(auditEventRepository.findAll()).extracting("action").contains("CHANNEL_GATEWAY_MESSAGE_RECEIVED", "BOT_MESSAGE_RECEIVED", "BOT_RFQ_DRAFT_CREATED");
  }

  @Test
  void unknownTelegramMessageRoutesToHumanHandoff() throws Exception {
    TenantContext.setTenantId(UUID.randomUUID());

    BotWebhookAckResponse response = service.handleTelegramUpdate(objectMapper.readTree("""
        {"update_id":1002,"message":{"message_id":502,"chat":{"id":90002},"text":"hello there"}}
        """));

    assertThat(response.intent()).isEqualTo(BotIntent.UNKNOWN);
    assertThat(response.status()).isEqualTo("HUMAN_REVIEW");
    assertThat(response.requiresHumanReview()).isTrue();
    assertThat(response.createdRfqDraftId()).isNull();
    assertThat(channelMessageRepository.count()).isEqualTo(1);
    assertThat(handoffRepository.count()).isEqualTo(1);
    assertThat(rfqRequestRepository.count()).isZero();
    assertThat(auditEventRepository.findAll()).extracting("action").contains("BOT_HUMAN_HANDOFF_CREATED");
  }

  @Test
  void botFlowDoesNotCreateApprovedQuoteFinalOrderChangeRequestOrMutateMasterData() throws Exception {
    TenantContext.setTenantId(UUID.randomUUID());
    long quotesBefore = draftQuoteRepository.count();
    long ordersBefore = draftOrderRepository.count();
    long inventoryBefore = inventorySnapshotRepository.count();
    long pricesBefore = priceRuleRepository.count();
    long customersBefore = customerAccountRepository.count();

    service.handleTelegramUpdate(objectMapper.readTree("""
        {"update_id":1003,"message":{"message_id":503,"chat":{"id":90003},"text":"Need commercial quote for filter set"}}
        """));

    assertThat(draftQuoteRepository.count()).isEqualTo(quotesBefore);
    assertThat(draftOrderRepository.count()).isEqualTo(ordersBefore);
    assertThat(inventorySnapshotRepository.count()).isEqualTo(inventoryBefore);
    assertThat(priceRuleRepository.count()).isEqualTo(pricesBefore);
    assertThat(customerAccountRepository.count()).isEqualTo(customersBefore);
    assertThat(changeRequestRepository.count()).isZero();
  }

  @Test
  void botDataIsTenantIsolatedAndTenantBDoesNotSeeTenantARecords() throws Exception {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);

    BotWebhookAckResponse response = service.handleTelegramUpdate(objectMapper.readTree("""
        {"update_id":1004,"message":{"message_id":504,"chat":{"id":90004},"text":"Need quote for oil filter"}}
        """));

    assertThat(rfqRequestRepository.findByIdAndTenantId(response.createdRfqDraftId(), tenantA)).isPresent();
    assertThat(rfqRequestRepository.findByIdAndTenantId(response.createdRfqDraftId(), tenantB)).isEmpty();
    assertThat(messageRepository.countByTenantIdAndChannel(tenantA, "TELEGRAM")).isEqualTo(1);
    assertThat(messageRepository.countByTenantIdAndChannel(tenantB, "TELEGRAM")).isZero();
    assertThat(channelMessageRepository.findByTenantIdOrderByReceivedAtDesc(tenantA)).hasSize(1);
    assertThat(channelMessageRepository.findByTenantIdOrderByReceivedAtDesc(tenantB)).isEmpty();
  }

  @Test
  void duplicateTelegramMessageIsRejectedAndDoesNotCreateDuplicateRfqOrChannelMessage() throws Exception {
    TenantContext.setTenantId(UUID.randomUUID());
    String payload = """
        {"update_id":1005,"message":{"message_id":505,"chat":{"id":90005},"text":"Need quote for belts"}}
        """;

    service.handleTelegramUpdate(objectMapper.readTree(payload));

    assertThatThrownBy(() -> service.handleTelegramUpdate(objectMapper.readTree(payload)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("already received");
    assertThat(rfqRequestRepository.count()).isEqualTo(1);
    assertThat(messageRepository.count()).isEqualTo(1);
    assertThat(channelMessageRepository.count()).isEqualTo(1);
  }

  @Test
  void missingTelegramTextIsRejectedSafely() throws Exception {
    TenantContext.setTenantId(UUID.randomUUID());

    assertThatThrownBy(() -> service.handleTelegramUpdate(objectMapper.readTree("""
        {"update_id":1006,"message":{"message_id":506,"chat":{"id":90006}}}
        """)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Malformed Telegram update payload");
    assertThat(messageRepository.count()).isZero();
    assertThat(channelMessageRepository.count()).isZero();
    assertThat(rfqRequestRepository.count()).isZero();
  }
}
