package com.orderpilot.application.services.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.Stage7Dtos.BotSimulateMessageRequest;
import com.orderpilot.api.dto.Stage7Dtos.BotSimulateMessageResponse;
import com.orderpilot.api.dto.Stage7Dtos.BotWebhookAckResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.IntakeValidationService;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.application.services.ProcessingJobService;
import com.orderpilot.application.services.channel.ChannelGatewayService;
import com.orderpilot.application.services.channel.ChannelIdentityService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.bot.*;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.integration.ChangeRequestRepository;
import com.orderpilot.domain.integration.ConnectorCommandRepository;
import com.orderpilot.domain.intake.ChannelMessageRepository;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.inventory.InventorySnapshot;
import com.orderpilot.domain.location.Location;
import com.orderpilot.domain.location.LocationRepository;
import com.orderpilot.domain.pricing.PriceRule;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.domain.workspace.ApprovalDecisionRepository;
import com.orderpilot.domain.workspace.ExceptionCase;
import com.orderpilot.domain.workspace.ExceptionCaseRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.Instant;
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
@Import({BotRuntimeService.class, BotResponseDraftService.class, BotReviewHandoffService.class, NoopTelegramOutboundTransport.class, RuleBasedBotIntentClassifier.class, BotPolicyService.class, BotWebhookSecurityService.class, ChannelGatewayService.class, ChannelIdentityService.class, IntakeValidationService.class, ProcessingJobService.class, AuditEventService.class, JsonSupport.class, ObjectMapper.class, CoreConfiguration.class})
class BotRuntimeServiceTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  @Autowired private BotRuntimeService service;
  @Autowired private BotResponseDraftService responseDraftService;
  @Autowired private BotReviewHandoffService reviewHandoffService;
  @Autowired private BotConversationRepository conversationRepository;
  @Autowired private BotMessageRepository messageRepository;
  @Autowired private BotRfqRequestRepository rfqRequestRepository;
  @Autowired private BotHandoffRepository handoffRepository;
  @Autowired private BotResponseDraftRepository responseDraftRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private ChannelMessageRepository channelMessageRepository;
  @Autowired private DraftQuoteRepository draftQuoteRepository;
  @Autowired private DraftOrderRepository draftOrderRepository;
  @Autowired private InventorySnapshotRepository inventorySnapshotRepository;
  @Autowired private PriceRuleRepository priceRuleRepository;
  @Autowired private CustomerAccountRepository customerAccountRepository;
  @Autowired private ProductRepository productRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private ChangeRequestRepository changeRequestRepository;
  @Autowired private ConnectorCommandRepository connectorCommandRepository;
  @Autowired private ApprovalDecisionRepository approvalDecisionRepository;
  @Autowired private ExceptionCaseRepository exceptionCaseRepository;
  @Autowired private BotConnectionRepository botConnectionRepository;
  @Autowired private BotRateLimitEventRepository botRateLimitEventRepository;

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
    assertThat(service.getConversation(response.conversationId()).getPolicyDecision()).isEqualTo(BotPolicyDecision.REQUIRE_OPERATOR_REVIEW.name());
    assertThat(conversationRepository.count()).isEqualTo(1);
    assertThat(messageRepository.count()).isEqualTo(1);
    assertThat(channelMessageRepository.count()).isEqualTo(1);
    assertThat(channelMessageRepository.findAll().get(0).getChannel()).isEqualTo("TELEGRAM");
    assertThat(rfqRequestRepository.count()).isEqualTo(1);
    assertThat(auditEventRepository.findAll()).extracting("action").contains("CHANNEL_GATEWAY_MESSAGE_RECEIVED", "BOT_MESSAGE_RECEIVED", "BOT_RFQ_DRAFT_CREATED");
    assertThat(auditEventRepository.findAll()).extracting("action").contains("BOT_INTENT_CLASSIFIED", "BOT_POLICY_DECISION_MADE");
  }

  @Test
  void unknownTelegramMessageRoutesToHumanHandoff() throws Exception {
    TenantContext.setTenantId(UUID.randomUUID());

    BotWebhookAckResponse response = service.handleTelegramUpdate(objectMapper.readTree("""
        {"update_id":1002,"message":{"message_id":502,"chat":{"id":90002},"text":"blue sky random note"}}
        """));

    assertThat(response.intent()).isEqualTo(BotIntent.UNKNOWN);
    assertThat(response.status()).isEqualTo("HUMAN_REVIEW");
    assertThat(response.requiresHumanReview()).isTrue();
    assertThat(response.createdRfqDraftId()).isNull();
    assertThat(service.getConversation(response.conversationId()).getPolicyDecision()).isEqualTo(BotPolicyDecision.BLOCK_UNSUPPORTED.name());
    assertThat(channelMessageRepository.count()).isEqualTo(1);
    assertThat(handoffRepository.count()).isEqualTo(1);
    assertThat(rfqRequestRepository.count()).isZero();
    assertThat(auditEventRepository.findAll()).extracting("action").contains("BOT_HUMAN_HANDOFF_CREATED");
  }

  @Test
  void deterministicClassifierUsesBoundedStage7IntentVocabulary() throws Exception {
    TenantContext.setTenantId(UUID.randomUUID());

    assertThat(service.handleTelegramUpdate(objectMapper.readTree("""
        {"update_id":1101,"message":{"message_id":601,"chat":{"id":90101},"text":"Is this filter in stock?"}}
        """)).intent()).isEqualTo(BotIntent.PRODUCT_AVAILABILITY_QUESTION);
    assertThat(service.handleTelegramUpdate(objectMapper.readTree("""
        {"update_id":1102,"message":{"message_id":602,"chat":{"id":90102},"text":"What is the price for SKU-1?"}}
        """)).intent()).isEqualTo(BotIntent.PRICE_QUESTION);
    assertThat(service.handleTelegramUpdate(objectMapper.readTree("""
        {"update_id":1103,"message":{"message_id":603,"chat":{"id":90103},"text":"Order status for PO-123?"}}
        """)).intent()).isEqualTo(BotIntent.ORDER_STATUS_QUESTION);
    assertThat(service.handleTelegramUpdate(objectMapper.readTree("""
        {"update_id":1104,"message":{"message_id":604,"chat":{"id":90104},"text":"Any substitute or alternative?"}}
        """)).intent()).isEqualTo(BotIntent.SUBSTITUTE_QUESTION);
    assertThat(service.handleTelegramUpdate(objectMapper.readTree("""
        {"update_id":1105,"message":{"message_id":605,"chat":{"id":90105},"text":"Need human help"}}
        """)).intent()).isEqualTo(BotIntent.HUMAN_HELP_REQUEST);
  }

  @Test
  void simulateRfqMessageCreatesConversationMessageAndRfqRequestDraft() {
    TenantContext.setTenantId(UUID.randomUUID());

    BotSimulateMessageResponse response = service.simulate(new BotSimulateMessageRequest("TELEGRAM", "demo-chat", "demo-msg-1", "Demo Buyer", "Need quote for 10 EA SKU-100", false));

    assertThat(response.intent()).isEqualTo(BotIntent.RFQ_REQUEST);
    assertThat(response.policyDecision()).isEqualTo(BotPolicyDecision.REQUIRE_OPERATOR_REVIEW);
    assertThat(response.requiresHumanReview()).isTrue();
    assertThat(response.createdRfqDraftId()).isNotNull();
    assertThat(response.suggestedSafeResponse()).contains("No quote, order, inventory, price, substitute, connector, or ERP action was executed");
    assertThat(conversationRepository.count()).isEqualTo(1);
    assertThat(messageRepository.count()).isEqualTo(1);
    assertThat(channelMessageRepository.count()).isEqualTo(1);
    assertThat(rfqRequestRepository.count()).isEqualTo(1);
    assertThat(draftQuoteRepository.count()).isZero();
    assertThat(draftOrderRepository.count()).isZero();
    assertThat(connectorCommandRepository.count()).isZero();
  }

  @Test
  void availabilityQuestionIsClassifiedAndRoutedToOperatorReviewWithoutInventoryQueryMutation() {
    TenantContext.setTenantId(UUID.randomUUID());
    long inventoryBefore = inventorySnapshotRepository.count();

    BotSimulateMessageResponse response = service.simulate(new BotSimulateMessageRequest("TELEGRAM", "demo-chat-availability", "demo-msg-2", "Demo Buyer", "Is SKU-100 in stock?", true));

    assertThat(response.intent()).isEqualTo(BotIntent.PRODUCT_AVAILABILITY_QUESTION);
    assertThat(response.policyDecision()).isEqualTo(BotPolicyDecision.REQUIRE_OPERATOR_REVIEW);
    assertThat(response.suggestedSafeResponse()).contains("operator review");
    assertThat(inventorySnapshotRepository.count()).isEqualTo(inventoryBefore);
    assertThat(rfqRequestRepository.count()).isZero();
  }

  @Test
  void priceQuestionWithoutKnownCustomerRequiresIdentificationAndHandoff() {
    TenantContext.setTenantId(UUID.randomUUID());

    BotSimulateMessageResponse response = service.simulate(new BotSimulateMessageRequest("TELEGRAM", "demo-chat-price", "demo-msg-3", "Unknown Buyer", "What is the price for SKU-100?", false));

    assertThat(response.intent()).isEqualTo(BotIntent.PRICE_QUESTION);
    assertThat(response.policyDecision()).isEqualTo(BotPolicyDecision.REQUIRE_CUSTOMER_IDENTIFICATION);
    assertThat(response.requiresHumanReview()).isTrue();
    assertThat(response.suggestedSafeResponse()).contains("cannot disclose price");
    assertThat(handoffRepository.count()).isEqualTo(1);
    assertThat(draftQuoteRepository.count()).isZero();
    assertThat(draftOrderRepository.count()).isZero();
  }

  @Test
  void unsupportedTextIsBlockedAndRoutedToHandoff() {
    TenantContext.setTenantId(UUID.randomUUID());

    BotSimulateMessageResponse response = service.simulate(new BotSimulateMessageRequest("TELEGRAM", "demo-chat-unknown", "demo-msg-4", "Unknown Buyer", "blue sky random note", false));

    assertThat(response.intent()).isEqualTo(BotIntent.UNKNOWN);
    assertThat(response.policyDecision()).isEqualTo(BotPolicyDecision.BLOCK_UNSUPPORTED);
    assertThat(response.suggestedSafeResponse()).contains("cannot handle");
    assertThat(handoffRepository.count()).isEqualTo(1);
    assertThat(rfqRequestRepository.count()).isZero();
  }

  @Test
  void operatorReviewAvailabilityDoesNotDiscloseSeededStock() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product product = productRepository.save(new Product(tenantId, "SKU-LEAK", "Leak Test", null, "Parts", null, null, "EA", "ACTIVE", new BigDecimal("10.00"), "USD", Instant.parse("2026-05-20T00:00:00Z")));
    Location location = locationRepository.save(new Location(tenantId, "ALM", "Almaty", "WAREHOUSE", null, "Almaty", "KZ", true, Instant.parse("2026-05-20T00:00:00Z")));
    inventorySnapshotRepository.save(new InventorySnapshot(tenantId, product.getId(), location.getId(), new BigDecimal("99"), new BigDecimal("77"), BigDecimal.ZERO, Instant.parse("2026-05-31T00:00:00Z"), "TEST", null, Instant.parse("2026-05-31T00:00:00Z")));

    BotSimulateMessageResponse response = service.simulate(new BotSimulateMessageRequest("TELEGRAM", "stock-no-leak", "stock-no-leak-msg", "Known Buyer", "Is SKU-LEAK in stock?", true));

    assertThat(response.policyDecision()).isEqualTo(BotPolicyDecision.REQUIRE_OPERATOR_REVIEW);
    assertThat(response.suggestedSafeResponse()).contains("operator review");
    assertThat(response.suggestedSafeResponse()).doesNotContain("77").doesNotContain("available in the latest stock snapshot");
    assertThat(handoffRepository.count()).isEqualTo(1);
    assertThat(auditEventRepository.findAll()).extracting("action").contains("BOT_POLICY_DENIED", "BOT_HANDOFF_CREATED");
  }

  @Test
  void operatorReviewPriceDoesNotDiscloseSeededCustomerPrice() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product product = productRepository.save(new Product(tenantId, "SKU-PRICE", "Price Test", null, "Parts", null, null, "EA", "ACTIVE", new BigDecimal("10.00"), "USD", Instant.parse("2026-05-20T00:00:00Z")));
    CustomerAccount customer = customerAccountRepository.save(new CustomerAccount(tenantId, "EXT-1", "CUST-1", "ACME", "ACME", null, "ACTIVE", "USD", null, Instant.parse("2026-05-20T00:00:00Z")));
    priceRuleRepository.save(new PriceRule(tenantId, product.getId(), customer.getId(), null, null, BigDecimal.ONE, "EA", new BigDecimal("123.45"), "USD", Instant.parse("2026-05-20T00:00:00Z"), null, 1, Instant.parse("2026-05-20T00:00:00Z")));

    BotSimulateMessageResponse response = service.simulate(new BotSimulateMessageRequest("TELEGRAM", "price-no-leak", "price-no-leak-msg", "Known Buyer", "What is the price for SKU-PRICE?", true));

    assertThat(response.policyDecision()).isEqualTo(BotPolicyDecision.REQUIRE_OPERATOR_REVIEW);
    assertThat(response.suggestedSafeResponse()).contains("operator review");
    assertThat(response.suggestedSafeResponse()).doesNotContain("123.45").doesNotContain("Customer-specific price");
    assertThat(handoffRepository.count()).isEqualTo(1);
    assertThat(auditEventRepository.findAll()).extracting("action").contains("BOT_POLICY_DENIED", "BOT_HANDOFF_CREATED");
  }

  @Test
  void disabledBotPolicyDeniesFlowAndCreatesContextualHandoff() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    service.updateSettings(false, java.util.List.of("REQUEST_QUOTE"), "BOT_REVIEW");

    BotSimulateMessageResponse response = service.simulate(new BotSimulateMessageRequest("TELEGRAM", "disabled-chat", "disabled-msg", "Buyer", "Need quote for 2 EA SKU-100", false));

    assertThat(response.policyDecision()).isEqualTo(BotPolicyDecision.BLOCK_UNSUPPORTED);
    assertThat(response.reasonCode()).isEqualTo("BOT_DISABLED");
    assertThat(response.requiresHumanReview()).isTrue();
    BotHandoff handoff = handoffRepository.findAll().get(0);
    assertThat(handoff.getChannelMessageId()).isNotNull();
    assertThat(handoff.getDetectedIntent()).isEqualTo(BotIntent.RFQ_REQUEST.name());
    assertThat(handoff.getAssignedQueue()).isEqualTo("BOT_REVIEW");
    assertThat(auditEventRepository.findAll()).extracting("action").contains("BOT_POLICY_DENIED", "BOT_HANDOFF_CREATED");
  }

  @Test
  void rateLimitBlocksExcessiveMessagesWithoutDuplicateRfqOrUnsafeWrites() throws Exception {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);

    BotWebhookAckResponse blocked = null;
    for (int i = 0; i < 21; i++) {
      blocked = service.handleTelegramUpdate(objectMapper.readTree("""
          {"update_id":%d,"message":{"message_id":%d,"chat":{"id":91234},"text":"Need quote for belts"}}
          """.formatted(2000 + i, 3000 + i)));
    }

    assertThat(blocked).isNotNull();
    assertThat(blocked.status()).isEqualTo("BOT_RATE_LIMITED");
    assertThat(botRateLimitEventRepository.countByTenantIdAndConversationKeyAndEventType(tenantId, "TELEGRAM:91234", "ACCEPTED")).isEqualTo(20);
    assertThat(botRateLimitEventRepository.countByTenantIdAndConversationKeyAndEventType(tenantId, "TELEGRAM:91234", "BLOCKED")).isEqualTo(1);
    assertThat(auditEventRepository.findAll()).extracting("action").contains("BOT_RATE_LIMITED");
    assertThat(draftOrderRepository.count()).isZero();
    assertThat(connectorCommandRepository.count()).isZero();
  }

  @Test
  void rateLimitAllowsExactlyConfiguredWindowBeforeBlocking() throws Exception {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    String conversationKey = "TELEGRAM:91300";

    for (int i = 0; i < 20; i++) {
      BotWebhookAckResponse response = service.handleTelegramUpdate(objectMapper.readTree("""
          {"update_id":%d,"message":{"message_id":%d,"chat":{"id":91300},"text":"Need quote for filters"}}
          """.formatted(3100 + i, 4100 + i)));
      assertThat(response.status()).isNotEqualTo("BOT_RATE_LIMITED");
    }

    assertThat(botRateLimitEventRepository.countByTenantIdAndConversationKeyAndEventType(tenantId, conversationKey, "ACCEPTED")).isEqualTo(20);
    assertThat(botRateLimitEventRepository.countByTenantIdAndConversationKeyAndEventType(tenantId, conversationKey, "BLOCKED")).isZero();
  }

  @Test
  void botSettingsPersistAllowedFlowsAndDisabledFlowCreatesPolicyHandoff() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    service.updateSettings(true, java.util.List.of("GREETING"), "HANDOFF_Q");

    BotSimulateMessageResponse response = service.simulate(new BotSimulateMessageRequest("TELEGRAM", "flow-chat", "flow-msg", "Buyer", "What is the price for SKU-100?", true));

    assertThat(botConnectionRepository.findFirstByTenantIdAndChannelTypeOrderByCreatedAtDesc(tenantId, "TELEGRAM")).isPresent();
    assertThat(response.policyDecision()).isEqualTo(BotPolicyDecision.BLOCK_UNSUPPORTED);
    assertThat(response.reasonCode()).isEqualTo("FLOW_DISABLED_CHECK_PRICE");
    assertThat(handoffRepository.findAll().get(0).getAssignedQueue()).isEqualTo("HANDOFF_Q");
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
    assertThat(connectorCommandRepository.count()).isZero();
    assertThat(approvalDecisionRepository.count()).isZero();
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
  void duplicateTelegramMessageIsIdempotentlyIgnoredAndDoesNotCreateDuplicateRfqOrChannelMessage() throws Exception {
    TenantContext.setTenantId(UUID.randomUUID());
    String payload = """
        {"update_id":1005,"message":{"message_id":505,"chat":{"id":90005},"text":"Need quote for belts"}}
        """;

    BotWebhookAckResponse first = service.handleTelegramUpdate(objectMapper.readTree(payload));
    BotWebhookAckResponse duplicate = service.handleTelegramUpdate(objectMapper.readTree(payload));

    assertThat(duplicate.status()).isEqualTo("DUPLICATE_IGNORED");
    assertThat(duplicate.messageId()).isEqualTo(first.messageId());
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

  @Test
  void operatorOnlyRuntimeCommandsCreateHandoffAndNeedsReviewWithoutApprovalsOrExternalWrites() throws Exception {
    TenantContext.setTenantId(UUID.randomUUID());
    BotWebhookAckResponse response = service.handleTelegramUpdate(objectMapper.readTree("""
        {"update_id":1007,"message":{"message_id":507,"chat":{"id":90007},"text":"What is the price?"}}
        """));
    long quoteCount = draftQuoteRepository.count();
    long orderCount = draftOrderRepository.count();
    long connectorCount = connectorCommandRepository.count();
    long changeRequestCount = changeRequestRepository.count();

    service.markRequiresReview(response.conversationId());
    service.createHandoff(response.conversationId(), response.messageId(), "OPERATOR_REQUESTED");

    assertThat(service.getConversation(response.conversationId()).isRequiresHumanReview()).isTrue();
    assertThat(service.listMessages(response.conversationId())).hasSize(1);
    assertThat(service.listHandoffs(response.conversationId())).isNotEmpty();
    assertThat(draftQuoteRepository.count()).isEqualTo(quoteCount);
    assertThat(draftOrderRepository.count()).isEqualTo(orderCount);
    assertThat(connectorCommandRepository.count()).isEqualTo(connectorCount);
    assertThat(changeRequestRepository.count()).isEqualTo(changeRequestCount);
    assertThat(auditEventRepository.findAll()).extracting("action").contains("BOT_CONVERSATION_MARKED_NEEDS_REVIEW", "BOT_HUMAN_HANDOFF_CREATED");
  }

  @Test
  void repeatedHandoffRequestsForSameMessageReuseExistingHandoff() throws Exception {
    TenantContext.setTenantId(UUID.randomUUID());
    BotWebhookAckResponse intake = service.handleTelegramUpdate(objectMapper.readTree("""
        {"update_id":1210,"message":{"message_id":710,"chat":{"id":90210},"text":"random unsupported text"}}
        """));

    service.createHandoff(intake.conversationId(), intake.messageId(), "FIRST_REASON");
    service.createHandoff(intake.conversationId(), intake.messageId(), "SECOND_REASON");

    assertThat(handoffRepository.findByTenantIdAndMessageIdOrderByCreatedAtDesc(TenantContext.requireTenantId(), intake.messageId())).hasSize(1);
  }

  @Test
  void rfqTelegramMessageCanProduceSafeOperatorReviewedResponseDraftAndStubSendWithoutExternalWrites() throws Exception {
    TenantContext.setTenantId(UUID.randomUUID());
    BotWebhookAckResponse intake = service.handleTelegramUpdate(objectMapper.readTree("""
        {"update_id":1201,"message":{"message_id":701,"chat":{"id":90201},"text":"Need quote for 20 EA SKU-200"}}
        """));
    long quoteCount = draftQuoteRepository.count();
    long orderCount = draftOrderRepository.count();
    long connectorCount = connectorCommandRepository.count();
    long inventoryCount = inventorySnapshotRepository.count();

    BotResponseDraft draft = responseDraftService.createDraft(intake.conversationId(), intake.messageId(), false);
    assertThat(draft.getResponseText()).isEqualTo("We received your request and created an RFQ draft for operator review.");
    assertThat(draft.getPolicyDecision()).isEqualTo(BotPolicyDecision.REQUIRE_OPERATOR_REVIEW.name());
    assertThat(draft.isRequiresOperatorReview()).isTrue();

    BotResponseDraft ready = responseDraftService.markReady(draft.getId(), UUID.randomUUID());
    assertThat(ready.getStatus()).isEqualTo("READY_FOR_STUB_SEND");

    BotResponseDraft sent = responseDraftService.stubSend(ready.getId());
    assertThat(sent.getStatus()).isEqualTo("STUB_SENT");
    assertThat(draftQuoteRepository.count()).isEqualTo(quoteCount);
    assertThat(draftOrderRepository.count()).isEqualTo(orderCount);
    assertThat(connectorCommandRepository.count()).isEqualTo(connectorCount);
    assertThat(inventorySnapshotRepository.count()).isEqualTo(inventoryCount);
    assertThat(auditEventRepository.findAll()).extracting("action").contains("BOT_RESPONSE_DRAFT_CREATED", "BOT_RESPONSE_DRAFT_MARKED_READY", "BOT_RESPONSE_STUB_SENT");
  }

  @Test
  void unknownMessageDoesNotProduceUnsafeResponseDraftAndIsAuditedAsBlocked() throws Exception {
    TenantContext.setTenantId(UUID.randomUUID());
    BotWebhookAckResponse intake = service.handleTelegramUpdate(objectMapper.readTree("""
        {"update_id":1202,"message":{"message_id":702,"chat":{"id":90202},"text":"random unrelated note"}}
        """));

    assertThatThrownBy(() -> responseDraftService.createDraft(intake.conversationId(), intake.messageId(), false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("UNKNOWN_OR_UNSUPPORTED_INTENT");
    assertThat(responseDraftRepository.count()).isZero();
    assertThat(auditEventRepository.findAll()).extracting("action").contains("BOT_RESPONSE_BLOCKED");
  }

  @Test
  void priceResponseWithoutTrustedCustomerIdentityRequiresIdentificationDraftAndReview() throws Exception {
    TenantContext.setTenantId(UUID.randomUUID());
    BotWebhookAckResponse intake = service.handleTelegramUpdate(objectMapper.readTree("""
        {"update_id":1203,"message":{"message_id":703,"chat":{"id":90203},"text":"What is the price for SKU-200?"}}
        """));

    BotResponseDraft draft = responseDraftService.createDraft(intake.conversationId(), intake.messageId(), false);

    assertThat(draft.getResponseType()).isEqualTo(BotIntent.PRICE_QUESTION.name());
    assertThat(draft.getPolicyDecision()).isEqualTo(BotPolicyDecision.REQUIRE_CUSTOMER_IDENTIFICATION.name());
    assertThat(draft.getResponseText()).contains("verify your customer identity");
    assertThat(draft.isRequiresOperatorReview()).isTrue();
    assertThat(auditEventRepository.findAll()).extracting("action").contains("BOT_RESPONSE_HANDOFF_REQUIRED");
  }

  @Test
  void availabilityAndSubstituteDraftsRemainBoundedAndOperatorReviewed() throws Exception {
    TenantContext.setTenantId(UUID.randomUUID());
    BotWebhookAckResponse availability = service.handleTelegramUpdate(objectMapper.readTree("""
        {"update_id":1204,"message":{"message_id":704,"chat":{"id":90204},"text":"Is SKU-200 in stock?"}}
        """));
    BotWebhookAckResponse substitute = service.handleTelegramUpdate(objectMapper.readTree("""
        {"update_id":1205,"message":{"message_id":705,"chat":{"id":90205},"text":"Any substitute for SKU-200?"}}
        """));

    BotResponseDraft availabilityDraft = responseDraftService.createDraft(availability.conversationId(), availability.messageId(), false);
    BotResponseDraft substituteDraft = responseDraftService.createDraft(substitute.conversationId(), substitute.messageId(), false);

    assertThat(availabilityDraft.getResponseText()).contains("confirm the exact product");
    assertThat(availabilityDraft.getResponseText()).doesNotContain("reserved").doesNotContain("delivery");
    assertThat(substituteDraft.getResponseText()).contains("operator will review substitute");
    assertThat(substituteDraft.getPolicyDecision()).isEqualTo(BotPolicyDecision.REQUIRE_OPERATOR_REVIEW.name());
    assertThat(availabilityDraft.isRequiresOperatorReview()).isTrue();
    assertThat(substituteDraft.isRequiresOperatorReview()).isTrue();
  }

  @Test
  void tenantCannotAccessAnotherTenantResponseDrafts() throws Exception {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    BotWebhookAckResponse intake = service.handleTelegramUpdate(objectMapper.readTree("""
        {"update_id":1206,"message":{"message_id":706,"chat":{"id":90206},"text":"Need quote for SKU-200"}}
        """));
    BotResponseDraft draft = responseDraftService.createDraft(intake.conversationId(), intake.messageId(), false);

    assertThat(responseDraftRepository.findByIdAndTenantId(draft.getId(), tenantA)).isPresent();
    assertThat(responseDraftRepository.findByIdAndTenantId(draft.getId(), tenantB)).isEmpty();
    TenantContext.setTenantId(tenantB);
    assertThatThrownBy(() -> responseDraftService.getDraft(draft.getId())).isInstanceOf(Exception.class);
    assertThatThrownBy(() -> responseDraftService.listDrafts(intake.conversationId())).isInstanceOf(Exception.class);
  }

  @Test
  void rfqBotConversationCreatesAndLinksOperatorReviewHandoffWithBotMetadata() throws Exception {
    TenantContext.setTenantId(UUID.randomUUID());
    BotWebhookAckResponse intake = service.handleTelegramUpdate(objectMapper.readTree("""
        {"update_id":1301,"message":{"message_id":801,"chat":{"id":90301},"text":"Need quote for 5 EA SKU-300"}}
        """));
    long quoteCount = draftQuoteRepository.count();
    long orderCount = draftOrderRepository.count();
    long connectorCount = connectorCommandRepository.count();
    long inventoryCount = inventorySnapshotRepository.count();

    var handoff = reviewHandoffService.createOrGet(intake.conversationId());
    ExceptionCase reviewCase = exceptionCaseRepository.findByIdAndTenantId(handoff.reviewCaseId(), TenantContext.requireTenantId()).orElseThrow();

    assertThat(handoff.reusedExisting()).isFalse();
    assertThat(handoff.sourceType()).isEqualTo("BOT_CONVERSATION");
    assertThat(handoff.sourceId()).isEqualTo(intake.conversationId());
    assertThat(handoff.rfqRequestId()).isEqualTo(intake.createdRfqDraftId());
    assertThat(handoff.detectedIntent()).isEqualTo("RFQ_REQUEST");
    assertThat(handoff.policyDecision()).isEqualTo(BotPolicyDecision.REQUIRE_OPERATOR_REVIEW.name());
    assertThat(handoff.latestMessage()).contains("Need quote");
    assertThat(handoff.nextActions()).contains("CREATE_MANUAL_RFQ_REVIEW", "OPERATOR_REPLY_DRAFT", "WAIT_FOR_CUSTOMER");
    assertThat(reviewCase.getSummary()).contains("intent=RFQ_REQUEST", "policyDecision=REQUIRE_OPERATOR_REVIEW", "rawText=\"Need quote");
    assertThat(service.getConversation(intake.conversationId()).getLinkedReviewCaseId()).isEqualTo(handoff.reviewCaseId());
    assertThat(draftQuoteRepository.count()).isEqualTo(quoteCount);
    assertThat(draftOrderRepository.count()).isEqualTo(orderCount);
    assertThat(connectorCommandRepository.count()).isEqualTo(connectorCount);
    assertThat(inventorySnapshotRepository.count()).isEqualTo(inventoryCount);
    assertThat(auditEventRepository.findAll()).extracting("action").contains("BOT_REVIEW_HANDOFF_CREATED", "BOT_CONVERSATION_LINKED_TO_REVIEW");
  }

  @Test
  void priceRequestWithoutTrustedIdentityCreatesReviewHandoffWithoutDrafts() throws Exception {
    TenantContext.setTenantId(UUID.randomUUID());
    BotWebhookAckResponse intake = service.handleTelegramUpdate(objectMapper.readTree("""
        {"update_id":1302,"message":{"message_id":802,"chat":{"id":90302},"text":"What is the price for SKU-300?"}}
        """));

    var handoff = reviewHandoffService.createOrGet(intake.conversationId());

    assertThat(handoff.summary()).contains("intent=PRICE_QUESTION", "handoffReason=");
    assertThat(handoff.nextActions()).contains("REQUEST_IDENTIFICATION", "OPERATOR_REPLY_DRAFT");
    assertThat(draftQuoteRepository.count()).isZero();
    assertThat(draftOrderRepository.count()).isZero();
    assertThat(connectorCommandRepository.count()).isZero();
  }

  @Test
  void unknownReviewHandoffIsIdempotentAndDoesNotCreateUnsafeDraftState() throws Exception {
    TenantContext.setTenantId(UUID.randomUUID());
    BotWebhookAckResponse intake = service.handleTelegramUpdate(objectMapper.readTree("""
        {"update_id":1303,"message":{"message_id":803,"chat":{"id":90303},"text":"hello random note"}}
        """));

    var first = reviewHandoffService.createOrGet(intake.conversationId());
    var second = reviewHandoffService.createOrGet(intake.conversationId());

    assertThat(first.reviewCaseId()).isEqualTo(second.reviewCaseId());
    assertThat(second.reusedExisting()).isTrue();
    assertThat(exceptionCaseRepository.findByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId())).hasSize(1);
    assertThat(draftQuoteRepository.count()).isZero();
    assertThat(draftOrderRepository.count()).isZero();
    assertThat(connectorCommandRepository.count()).isZero();
    assertThat(auditEventRepository.findAll()).extracting("action").contains("BOT_REVIEW_HANDOFF_REUSED");
  }

  @Test
  void tenantCannotCreateReviewHandoffForAnotherTenantConversation() throws Exception {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    BotWebhookAckResponse intake = service.handleTelegramUpdate(objectMapper.readTree("""
        {"update_id":1304,"message":{"message_id":804,"chat":{"id":90304},"text":"Need quote for SKU-300"}}
        """));

    TenantContext.setTenantId(tenantB);

    assertThatThrownBy(() -> reviewHandoffService.createOrGet(intake.conversationId())).isInstanceOf(Exception.class);
    assertThat(exceptionCaseRepository.findByTenantIdOrderByCreatedAtDesc(tenantB)).isEmpty();
  }
}
