package com.orderpilot.application.services.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.orderpilot.api.dto.Stage12BDtos.ChannelToQuoteRequest;
import com.orderpilot.api.dto.Stage12BDtos.ChannelToQuoteResponse;
import com.orderpilot.api.dto.Stage7Dtos.BotSimulateMessageRequest;
import com.orderpilot.api.dto.Stage7Dtos.BotSimulateMessageResponse;
import com.orderpilot.api.dto.Stage7Dtos.BotWebhookAckResponse;
import com.orderpilot.application.services.ProductCatalogMatchingService;
import com.orderpilot.application.services.ProductCatalogMatchingService.ProductCatalogMatchResult;
import com.orderpilot.application.services.ProductSubstitutionService;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.application.services.channel.ChannelGatewayService;
import com.orderpilot.application.services.channel.ChannelType;
import com.orderpilot.application.services.channel.NormalizedInboundMessage;
import com.orderpilot.application.services.channel.WebhookVerificationMode;
import com.orderpilot.application.services.workspace.ChannelToQuoteWiringService;
import com.orderpilot.application.services.workspace.PricingService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.domain.bot.*;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.inventory.InventorySnapshot;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.intake.ChannelMessage;
import com.orderpilot.domain.pricing.PriceRule;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.tenant.TenantRepository;
import com.orderpilot.domain.workspace.ExceptionCaseRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BotRuntimeService {
  private static final String CHANNEL_TELEGRAM = "TELEGRAM";
  private static final int RATE_LIMIT_PER_MINUTE = 20;
  private static final List<String> DEFAULT_ALLOWED_FLOWS = List.of(
      "GREETING", "CHECK_AVAILABILITY", "CHECK_PRICE", "REQUEST_QUOTE",
      "SUGGEST_SUBSTITUTE", "ORDER_OR_QUOTE_STATUS", "HUMAN_HANDOFF", "UNSUPPORTED_REQUEST_SAFE_REPLY");
  private final BotConversationRepository conversationRepository;
  private final BotMessageRepository messageRepository;
  private final BotRfqRequestRepository rfqRequestRepository;
  private final BotHandoffRepository handoffRepository;
  private final BotConnectionRepository connectionRepository;
  private final BotRateLimitEventRepository rateLimitEventRepository;
  private final RuleBasedBotIntentClassifier intentClassifier;
  private final BotPolicyService policyService;
  private final BotWebhookSecurityService webhookSecurityService;
  private final AuditEventService auditEventService;
  private final ChannelGatewayService channelGatewayService;
  private final ExceptionCaseRepository reviewCaseRepository;
  private final ProductCatalogMatchingService productCatalogMatchingService;
  private final ProductSubstitutionService productSubstitutionService;
  private final PricingService pricingService;
  private final ChannelToQuoteWiringService channelToQuoteWiringService;
  private final ProductRepository productRepository;
  private final InventorySnapshotRepository inventorySnapshotRepository;
  private final CustomerAccountRepository customerAccountRepository;
  private final TenantRepository tenantRepository;
  private final JsonSupport jsonSupport;
  private final Clock clock;

  public BotRuntimeService(
      BotConversationRepository conversationRepository,
      BotMessageRepository messageRepository,
      BotRfqRequestRepository rfqRequestRepository,
      BotHandoffRepository handoffRepository,
      BotConnectionRepository connectionRepository,
      BotRateLimitEventRepository rateLimitEventRepository,
      RuleBasedBotIntentClassifier intentClassifier,
      BotPolicyService policyService,
      BotWebhookSecurityService webhookSecurityService,
      AuditEventService auditEventService,
      ChannelGatewayService channelGatewayService,
      ExceptionCaseRepository reviewCaseRepository,
      ObjectProvider<ProductCatalogMatchingService> productCatalogMatchingService,
      ObjectProvider<ProductSubstitutionService> productSubstitutionService,
      ObjectProvider<PricingService> pricingService,
      ObjectProvider<ChannelToQuoteWiringService> channelToQuoteWiringService,
      ProductRepository productRepository,
      InventorySnapshotRepository inventorySnapshotRepository,
      CustomerAccountRepository customerAccountRepository,
      TenantRepository tenantRepository,
      JsonSupport jsonSupport,
      Clock clock) {
    this.conversationRepository = conversationRepository;
    this.messageRepository = messageRepository;
    this.rfqRequestRepository = rfqRequestRepository;
    this.handoffRepository = handoffRepository;
    this.connectionRepository = connectionRepository;
    this.rateLimitEventRepository = rateLimitEventRepository;
    this.intentClassifier = intentClassifier;
    this.policyService = policyService;
    this.webhookSecurityService = webhookSecurityService;
    this.auditEventService = auditEventService;
    this.channelGatewayService = channelGatewayService;
    this.reviewCaseRepository = reviewCaseRepository;
    this.productCatalogMatchingService = productCatalogMatchingService.getIfAvailable();
    this.productSubstitutionService = productSubstitutionService.getIfAvailable();
    this.pricingService = pricingService.getIfAvailable();
    this.channelToQuoteWiringService = channelToQuoteWiringService.getIfAvailable();
    this.productRepository = productRepository;
    this.inventorySnapshotRepository = inventorySnapshotRepository;
    this.customerAccountRepository = customerAccountRepository;
    this.tenantRepository = tenantRepository;
    this.jsonSupport = jsonSupport;
    this.clock = clock;
  }

  @Transactional
  public BotWebhookAckResponse handleTelegramUpdate(JsonNode update) {
    return handleTelegramUpdate(update, WebhookVerificationMode.NOT_CONFIGURED_STAGE_10E);
  }

  @Transactional
  public BotWebhookAckResponse handleTelegramUpdate(JsonNode update, WebhookVerificationMode verificationMode) {
    TelegramMessage incoming = parse(update);
    UUID tenantId = TenantContext.requireTenantId();
    requireSeededTenant(tenantId);
    Instant now = clock.instant();
    String externalMessageId = incoming.updateId() == null ? incoming.messageId() : incoming.updateId() + ":" + incoming.messageId();
    Optional<BotMessage> existingMessage = messageRepository.findFirstByTenantIdAndChannelAndExternalChatIdAndExternalMessageId(tenantId, CHANNEL_TELEGRAM, incoming.chatId(), externalMessageId);
    if (existingMessage.isPresent()) {
      BotMessage existing = existingMessage.get();
      auditEventService.record("BOT_MESSAGE_DUPLICATE_IGNORED", "BOT_MESSAGE", existing.getId().toString(), null, "{\"externalExecution\":\"DISABLED\"}");
      return new BotWebhookAckResponse(existing.getConversationId(), existing.getId(), existing.getDetectedIntent(), "DUPLICATE_IGNORED", "Duplicate Telegram message ignored. No business records were changed.", existing.isRequiresHumanReview(), null);
    }
    ChannelMessage channelMessage = channelGatewayService.accept(new NormalizedInboundMessage(tenantId, ChannelType.TELEGRAM, externalMessageId, incoming.chatId(), incoming.chatId(), "Telegram User", null, incoming.rawText(), List.of(), now, update.toString(), "TELEGRAM:" + incoming.chatId() + ":" + externalMessageId), verificationMode);
    BotIntent intent = intentClassifier.detect(incoming.rawText());
    String flow = flowFor(intent);
    BotConnection connection = resolveConnection(tenantId, CHANNEL_TELEGRAM, now);
    if (rateLimited(tenantId, CHANNEL_TELEGRAM + ":" + incoming.chatId(), now)) {
      auditEventService.record("BOT_RATE_LIMITED", "BOT_CONNECTION", connection.getId().toString(), null, auditJson(connection.getId(), null, channelMessage.getId(), channelMessage.getCustomerAccountId(), intent, flow, "RATE_LIMITED", null, null, "BOT", "DISABLED"));
      return capturedWithHandoff(tenantId, channelMessage, incoming.chatId(), externalMessageId, incoming.rawText(), intent, "BOT_RATE_LIMITED", "Too many messages were received. This conversation was routed to an operator.", true, connection, List.of("RATE_LIMITED"));
    }
    BotPolicyService.PolicyResult policy = policyFor(connection, flow, intent, channelMessage.getCustomerAccountId() != null);
    boolean requiresReview = policy.requiresHumanReview();

    BotConversation conversation = conversationRepository.findByTenantIdAndChannelAndExternalChatId(tenantId, CHANNEL_TELEGRAM, incoming.chatId())
        .orElseGet(() -> conversationRepository.save(new BotConversation(tenantId, CHANNEL_TELEGRAM, incoming.chatId(), now)));
    conversation.touch(requiresReview ? "HUMAN_REVIEW" : "OPEN", requiresReview, now);
    conversation.applyPolicy(policy.decision().name(), policy.suggestedNextAction(), now);
    conversation = conversationRepository.save(conversation);

    webhookSecurityService.rejectReplay(tenantId, incoming.chatId(), externalMessageId);

    BotMessage message = saveMessageWithAudit(tenantId, conversation, channelMessage, CHANNEL_TELEGRAM, incoming.chatId(), externalMessageId, incoming.rawText(), intent, policy, connection, flow, requiresReview, false);
    return executeFlow(tenantId, conversation, message, channelMessage, incoming.rawText(), intent, policy, connection, flow, false);
  }

  @Transactional
  public BotSimulateMessageResponse simulate(BotSimulateMessageRequest request) {
    if (request == null || request.text() == null || request.text().isBlank()) {
      throw new IllegalArgumentException("Bot message text is required");
    }
    UUID tenantId = TenantContext.requireTenantId();
    Instant now = clock.instant();
    String channel = request.channel() == null || request.channel().isBlank() ? CHANNEL_TELEGRAM : request.channel().trim().toUpperCase();
    String chatId = request.externalChatId() == null || request.externalChatId().isBlank() ? "SIMULATED_CHAT" : request.externalChatId().trim();
    String messageId = request.externalMessageId() == null || request.externalMessageId().isBlank() ? "SIM-" + UUID.randomUUID() : request.externalMessageId().trim();
    String rawText = request.text().trim();

    Optional<BotMessage> duplicate = messageRepository.findFirstByTenantIdAndChannelAndExternalChatIdAndExternalMessageId(tenantId, channel, chatId, messageId);
    if (duplicate.isPresent()) {
      BotMessage existing = duplicate.get();
      return new BotSimulateMessageResponse(existing.getConversationId(), existing.getId(), existing.getDetectedIntent(), BotPolicyDecision.REQUIRE_OPERATOR_REVIEW, "DUPLICATE_IGNORED", "Duplicate simulated message ignored. No business records were changed.", existing.isRequiresHumanReview(), null);
    }

    BotIntent intent = intentClassifier.detect(rawText);
    String flow = flowFor(intent);
    BotConnection connection = resolveConnection(tenantId, channel, now);
    BotPolicyService.PolicyResult policy = policyFor(connection, flow, intent, request.knownCustomerIdentity());
    boolean requiresReview = policy.requiresHumanReview();

    BotConversation conversation = conversationRepository.findByTenantIdAndChannelAndExternalChatId(tenantId, channel, chatId)
        .orElseGet(() -> conversationRepository.save(new BotConversation(tenantId, channel, chatId, now)));
    conversation.touch(requiresReview ? "HUMAN_REVIEW" : "OPEN", requiresReview, now);
    conversation.applyPolicy(policy.decision().name(), policy.suggestedNextAction(), now);
    conversation = conversationRepository.save(conversation);

    ChannelMessage channelMessage = channelGatewayService.accept(new NormalizedInboundMessage(tenantId, ChannelType.valueOf(channel), messageId, chatId, chatId, request.senderDisplayName(), null, rawText, List.of(), now, "{\"simulation\":true}", "BOT_SIM:" + channel + ":" + chatId + ":" + messageId));

    BotMessage message = saveMessageWithAudit(tenantId, conversation, channelMessage, channel, chatId, messageId, rawText, intent, policy, connection, flow, requiresReview, true);
    BotWebhookAckResponse response = executeFlow(tenantId, conversation, message, channelMessage, rawText, intent, policy, connection, flow, true);
    return new BotSimulateMessageResponse(conversation.getId(), message.getId(), intent, policy.decision(), policy.reasonCode(), response.responseMessage(), response.requiresHumanReview(), response.createdRfqDraftId());
  }

  @Transactional(readOnly = true)
  public BotConnection getSettings() {
    return connectionRepository.findFirstByTenantIdAndChannelTypeOrderByCreatedAtDesc(TenantContext.requireTenantId(), CHANNEL_TELEGRAM)
        .orElseGet(() -> new BotConnection(TenantContext.requireTenantId(), CHANNEL_TELEGRAM, null, null, true, allowedFlowsJson(DEFAULT_ALLOWED_FLOWS), "BOT_REVIEW", "{}", clock.instant()));
  }

  @Transactional
  public BotConnection updateSettings(Boolean enabled, List<String> allowedFlows, String defaultHandoffQueue) {
    UUID tenantId = TenantContext.requireTenantId();
    BotConnection connection = connectionRepository.findFirstByTenantIdAndChannelTypeOrderByCreatedAtDesc(tenantId, CHANNEL_TELEGRAM)
        .orElseGet(() -> connectionRepository.save(new BotConnection(tenantId, CHANNEL_TELEGRAM, null, null, true, allowedFlowsJson(DEFAULT_ALLOWED_FLOWS), "BOT_REVIEW", "{}", clock.instant())));
    List<String> sanitizedFlows = allowedFlows == null ? allowedFlows(connection) : allowedFlows.stream().map(this::normalizeFlow).filter(flow -> DEFAULT_ALLOWED_FLOWS.contains(flow)).distinct().toList();
    connection.configure(enabled == null ? connection.isEnabled() : enabled, allowedFlowsJson(sanitizedFlows.isEmpty() ? DEFAULT_ALLOWED_FLOWS : sanitizedFlows), defaultHandoffQueue, clock.instant());
    BotConnection saved = connectionRepository.save(connection);
    auditEventService.record("BOT_SETTINGS_UPDATED", "BOT_CONNECTION", saved.getId().toString(), null, "{\"allowedFlows\":" + saved.getAllowedFlows() + ",\"enabled\":" + saved.isEnabled() + ",\"externalExecution\":\"DISABLED\"}");
    return saved;
  }

  @Transactional(readOnly = true)
  public List<BotHandoff> listHandoffQueue() {
    return handoffRepository.findByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId());
  }

  @Transactional(readOnly = true)
  public List<BotConversation> listConversations() {
    return conversationRepository.findByTenantIdOrderByUpdatedAtDesc(TenantContext.requireTenantId());
  }

  @Transactional(readOnly = true)
  public BotConversation getConversation(UUID id) {
    return conversationRepository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow();
  }

  @Transactional(readOnly = true)
  public List<BotMessage> listMessages(UUID conversationId) {
    getConversation(conversationId);
    return messageRepository.findByTenantIdAndConversationIdOrderByCreatedAtAsc(TenantContext.requireTenantId(), conversationId);
  }

  @Transactional(readOnly = true)
  public BotMessage getMessage(UUID id) {
    return messageRepository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow();
  }

  @Transactional(readOnly = true)
  public List<BotHandoff> listHandoffs(UUID conversationId) {
    getConversation(conversationId);
    return handoffRepository.findByTenantIdAndConversationIdOrderByCreatedAtDesc(TenantContext.requireTenantId(), conversationId);
  }

  @Transactional
  public BotHandoff createHandoff(UUID conversationId, UUID messageId, String reason) {
    return createHandoff(conversationId, messageId, reason, true);
  }

  private BotWebhookAckResponse executeFlow(UUID tenantId, BotConversation conversation, BotMessage message, ChannelMessage channelMessage, String rawText, BotIntent intent, BotPolicyService.PolicyResult policy, BotConnection connection, String flow, boolean simulation) {
    if (policy.decision() == BotPolicyDecision.BLOCK_UNSUPPORTED || policy.decision() == BotPolicyDecision.REQUIRE_CUSTOMER_IDENTIFICATION) {
      auditEventService.record("BOT_POLICY_DENIED", "BOT_MESSAGE", message.getId().toString(), null, auditJson(connection.getId(), conversation.getId(), channelMessage.getId(), channelMessage.getCustomerAccountId(), intent, flow, policy.reasonCode(), null, null, "BOT", "DISABLED"));
      createHandoff(conversation.getId(), message.getId(), policy.reasonCode(), false, channelMessage, intent, connection.getDefaultHandoffQueue(), hintsJson(rawText), riskFlags(policy.reasonCode()));
      return new BotWebhookAckResponse(conversation.getId(), message.getId(), intent, "HUMAN_REVIEW", safeResponse(intent, policy), true, null);
    }
    if (policy.decision() == BotPolicyDecision.REQUIRE_OPERATOR_REVIEW || policy.decision() == BotPolicyDecision.REQUIRE_HUMAN_HANDOFF) {
      return operatorReviewOnly(tenantId, conversation, message, channelMessage, rawText, intent, policy, connection, flow);
    }
    auditEventService.record("BOT_FLOW_STARTED", "BOT_MESSAGE", message.getId().toString(), null, auditJson(connection.getId(), conversation.getId(), channelMessage.getId(), channelMessage.getCustomerAccountId(), intent, flow, policy.reasonCode(), null, null, "BOT", "DISABLED"));
    return switch (flow) {
      case "GREETING" -> respond(conversation, message, intent, "GREETING_SAFE_REPLY", "Hello. Send a part number, quantity, or RFQ and I will route it through operator-controlled OrderPilot workflows.", false, connection, channelMessage, flow);
      case "CHECK_AVAILABILITY" -> availabilityFlow(tenantId, conversation, message, channelMessage, rawText, intent, connection, flow);
      case "CHECK_PRICE" -> priceFlow(tenantId, conversation, message, channelMessage, rawText, intent, connection, flow);
      case "REQUEST_QUOTE" -> rfqFlow(tenantId, conversation, message, channelMessage, rawText, intent, connection, flow, simulation);
      case "SUGGEST_SUBSTITUTE" -> substituteFlow(tenantId, conversation, message, channelMessage, rawText, intent, connection, flow);
      case "ORDER_OR_QUOTE_STATUS" -> {
        createHandoff(conversation.getId(), message.getId(), "ORDER_STATUS_REQUIRES_OPERATOR_REVIEW", false, channelMessage, intent, connection.getDefaultHandoffQueue(), hintsJson(rawText), riskFlags("CUSTOMER_DATA_DISCLOSURE"));
        yield new BotWebhookAckResponse(conversation.getId(), message.getId(), intent, "HUMAN_REVIEW", "I routed the status request to an operator so customer details can be verified first.", true, null);
      }
      case "HUMAN_HANDOFF" -> {
        createHandoff(conversation.getId(), message.getId(), "HUMAN_HELP_REQUESTED", false, channelMessage, intent, connection.getDefaultHandoffQueue(), hintsJson(rawText), riskFlags("CUSTOMER_REQUESTED_HUMAN"));
        yield new BotWebhookAckResponse(conversation.getId(), message.getId(), intent, "HUMAN_REVIEW", "I routed this conversation to a human operator.", true, null);
      }
      default -> {
        createHandoff(conversation.getId(), message.getId(), "UNKNOWN_OR_UNSUPPORTED_INTENT", false, channelMessage, intent, connection.getDefaultHandoffQueue(), hintsJson(rawText), riskFlags("UNSUPPORTED"));
        yield new BotWebhookAckResponse(conversation.getId(), message.getId(), intent, "HUMAN_REVIEW", "I cannot handle that automatically. I routed it to a human operator.", true, null);
      }
    };
  }

  private BotWebhookAckResponse operatorReviewOnly(UUID tenantId, BotConversation conversation, BotMessage message, ChannelMessage channelMessage, String rawText, BotIntent intent, BotPolicyService.PolicyResult policy, BotConnection connection, String flow) {
    auditEventService.record("BOT_POLICY_DENIED", "BOT_MESSAGE", message.getId().toString(), null, auditJson(connection.getId(), conversation.getId(), channelMessage.getId(), channelMessage.getCustomerAccountId(), intent, flow, policy.reasonCode(), null, null, "BOT", "DISABLED"));
    if (intent == BotIntent.RFQ_REQUEST) {
      BotRfqRequest rfq = rfqRequestRepository.findByTenantIdAndMessageIdOrderByCreatedAtDesc(tenantId, message.getId()).stream().findFirst()
          .orElseGet(() -> rfqRequestRepository.save(new BotRfqRequest(tenantId, conversation.getId(), message.getId(), message.getChannel(), rawText, normalizeMinimalRequestText(rawText), clock.instant())));
      auditEventService.record("BOT_RFQ_CREATED", "BOT_RFQ_REQUEST", rfq.getId().toString(), null, auditJson(connection.getId(), conversation.getId(), channelMessage.getId(), channelMessage.getCustomerAccountId(), intent, flow, "RFQ_CAPTURED_FOR_OPERATOR_REVIEW", null, null, "BOT", "DISABLED"));
      auditEventService.record("BOT_RFQ_DRAFT_CREATED", "BOT_RFQ_REQUEST", rfq.getId().toString(), null, "{\"source\":\"" + message.getChannel() + "\",\"messageId\":\"" + message.getId() + "\",\"externalExecution\":\"DISABLED\"}");
      return new BotWebhookAckResponse(conversation.getId(), message.getId(), intent, "RFQ_DRAFT_REQUIRES_HUMAN_REVIEW", safeResponse(intent, policy), true, rfq.getId());
    }
    createHandoff(conversation.getId(), message.getId(), policy.reasonCode(), false, channelMessage, intent, connection.getDefaultHandoffQueue(), hintsJson(rawText), riskFlags(policy.reasonCode()));
    return new BotWebhookAckResponse(conversation.getId(), message.getId(), intent, "HUMAN_REVIEW", safeResponse(intent, policy), true, null);
  }

  private BotWebhookAckResponse availabilityFlow(UUID tenantId, BotConversation conversation, BotMessage message, ChannelMessage channelMessage, String rawText, BotIntent intent, BotConnection connection, String flow) {
    ProductCatalogMatchResult match = matchProduct(tenantId, rawText, channelMessage.getCustomerAccountId());
    if (match == null || !match.matched() || match.requiresReview() || match.productId() == null) {
      createHandoff(conversation.getId(), message.getId(), "PRODUCT_MATCH_REQUIRES_OPERATOR_REVIEW", false, channelMessage, intent, connection.getDefaultHandoffQueue(), hintsJson(rawText), riskFlags("AMBIGUOUS_PRODUCT"));
      return new BotWebhookAckResponse(conversation.getId(), message.getId(), intent, "HUMAN_REVIEW", "I found that the product needs operator review before availability can be shared.", true, null);
    }
    Optional<InventorySnapshot> snapshot = inventorySnapshotRepository.findTop50ByTenantIdAndProductIdOrderByCapturedAtDesc(tenantId, match.productId()).stream().findFirst();
    if (snapshot.isEmpty() || snapshot.get().getCapturedAt().isBefore(clock.instant().minus(Duration.ofHours(24)))) {
      auditEventService.record("BOT_AVAILABILITY_CHECKED", "BOT_MESSAGE", message.getId().toString(), null, auditJson(connection.getId(), conversation.getId(), channelMessage.getId(), channelMessage.getCustomerAccountId(), intent, flow, "STALE_OR_MISSING_INVENTORY", null, null, "BOT", "DISABLED"));
      createHandoff(conversation.getId(), message.getId(), "INVENTORY_STALE_OR_MISSING", false, channelMessage, intent, connection.getDefaultHandoffQueue(), hintsJson(rawText), riskFlags("STALE_INVENTORY"));
      return new BotWebhookAckResponse(conversation.getId(), message.getId(), intent, "HUMAN_REVIEW", "Inventory data needs operator confirmation before I answer. I routed this to review.", true, null);
    }
    auditEventService.record("BOT_AVAILABILITY_CHECKED", "BOT_MESSAGE", message.getId().toString(), null, auditJson(connection.getId(), conversation.getId(), channelMessage.getId(), channelMessage.getCustomerAccountId(), intent, flow, "FRESH_SNAPSHOT", null, null, "BOT", "DISABLED"));
    String stock = snapshot.get().getQuantityAvailable().compareTo(BigDecimal.ZERO) > 0 ? "available in the latest stock snapshot" : "not available in the latest stock snapshot";
    return respond(conversation, message, intent, "AVAILABILITY_CHECKED", match.matchedSku() + " is " + stock + ". This is not a delivery promise.", false, connection, channelMessage, flow);
  }

  private BotWebhookAckResponse priceFlow(UUID tenantId, BotConversation conversation, BotMessage message, ChannelMessage channelMessage, String rawText, BotIntent intent, BotConnection connection, String flow) {
    if (channelMessage.getCustomerAccountId() == null) {
      auditEventService.record("BOT_POLICY_DENIED", "BOT_MESSAGE", message.getId().toString(), null, auditJson(connection.getId(), conversation.getId(), channelMessage.getId(), null, intent, flow, "UNKNOWN_CUSTOMER_IDENTITY", null, null, "BOT", "DISABLED"));
      createHandoff(conversation.getId(), message.getId(), "UNKNOWN_CUSTOMER_IDENTITY", false, channelMessage, intent, connection.getDefaultHandoffQueue(), hintsJson(rawText), riskFlags("PRICE_REQUIRES_CUSTOMER"));
      return new BotWebhookAckResponse(conversation.getId(), message.getId(), intent, "CUSTOMER_IDENTIFICATION_REQUIRED", "An operator needs to verify your customer identity before price information can be shared.", true, null);
    }
    ProductCatalogMatchResult match = matchProduct(tenantId, rawText, channelMessage.getCustomerAccountId());
    Optional<CustomerAccount> customer = customerAccountRepository.findByIdAndTenantIdAndDeletedAtIsNull(channelMessage.getCustomerAccountId(), tenantId);
    Optional<Product> product = match == null || match.productId() == null ? Optional.empty() : productRepository.findByIdAndTenantIdAndDeletedAtIsNull(match.productId(), tenantId);
    if (pricingService == null || customer.isEmpty() || product.isEmpty()) {
      createHandoff(conversation.getId(), message.getId(), "PRICE_LOOKUP_REQUIRES_OPERATOR_REVIEW", false, channelMessage, intent, connection.getDefaultHandoffQueue(), hintsJson(rawText), riskFlags("PRICE_UNAVAILABLE"));
      return new BotWebhookAckResponse(conversation.getId(), message.getId(), intent, "HUMAN_REVIEW", "I routed the price request to an operator for customer-specific review.", true, null);
    }
    Optional<PriceRule> price = pricingService.selectPrice(tenantId, product.get(), customer.get(), null, extractedQuantity(rawText), "EA");
    auditEventService.record("BOT_PRICE_CHECKED", "BOT_MESSAGE", message.getId().toString(), null, auditJson(connection.getId(), conversation.getId(), channelMessage.getId(), customer.get().getId(), intent, flow, price.isPresent() ? "PRICE_RULE_FOUND" : "PRICE_RULE_MISSING", null, null, "BOT", "DISABLED"));
    if (price.isEmpty()) {
      createHandoff(conversation.getId(), message.getId(), "PRICE_RULE_MISSING", false, channelMessage, intent, connection.getDefaultHandoffQueue(), hintsJson(rawText), riskFlags("PRICE_REVIEW_REQUIRED"));
      return new BotWebhookAckResponse(conversation.getId(), message.getId(), intent, "HUMAN_REVIEW", "I routed the price request to an operator because no safe price rule matched.", true, null);
    }
    return respond(conversation, message, intent, "PRICE_CHECKED", "Customer-specific price for " + product.get().getSku() + " is " + price.get().getUnitPrice() + " " + price.get().getCurrency() + ". No discount or margin approval was performed.", false, connection, channelMessage, flow);
  }

  private BotWebhookAckResponse rfqFlow(UUID tenantId, BotConversation conversation, BotMessage message, ChannelMessage channelMessage, String rawText, BotIntent intent, BotConnection connection, String flow, boolean simulation) {
    BotRfqRequest rfq = rfqRequestRepository.findByTenantIdAndMessageIdOrderByCreatedAtDesc(tenantId, message.getId()).stream().findFirst()
        .orElseGet(() -> rfqRequestRepository.save(new BotRfqRequest(tenantId, conversation.getId(), message.getId(), message.getChannel(), rawText, normalizeMinimalRequestText(rawText), clock.instant())));
    auditEventService.record("BOT_RFQ_CREATED", "BOT_RFQ_REQUEST", rfq.getId().toString(), null, auditJson(connection.getId(), conversation.getId(), channelMessage.getId(), channelMessage.getCustomerAccountId(), intent, flow, "RFQ_CAPTURED", null, null, "BOT", "DISABLED"));
    auditEventService.record("BOT_RFQ_DRAFT_CREATED", "BOT_RFQ_REQUEST", rfq.getId().toString(), null, "{\"source\":\"" + message.getChannel() + "\",\"messageId\":\"" + message.getId() + "\",\"externalExecution\":\"DISABLED\"}");
    UUID quoteId = null;
    if (!simulation && channelToQuoteWiringService != null) {
      try {
        ChannelToQuoteResponse response = channelToQuoteWiringService.createFromChannelMessage(channelMessage.getId(), new ChannelToQuoteRequest("bot:" + message.getId(), channelMessage.getCustomerAccountId(), "STANDARD", "Created by controlled bot runtime for operator review", false, true, List.of(), Map.of()), null, "BOT");
        quoteId = response.quoteId();
        auditEventService.record("BOT_QUOTE_DRAFT_REQUESTED", "QUOTE_CONVERSION_ATTEMPT", response.conversionAttemptId().toString(), null, auditJson(connection.getId(), conversation.getId(), channelMessage.getId(), channelMessage.getCustomerAccountId(), intent, flow, response.status(), quoteId, null, "BOT", "DISABLED"));
      } catch (RuntimeException ex) {
        auditEventService.record("BOT_ERROR", "BOT_MESSAGE", message.getId().toString(), null, auditJson(connection.getId(), conversation.getId(), channelMessage.getId(), channelMessage.getCustomerAccountId(), intent, flow, "QUOTE_DRAFT_REQUEST_FAILED", null, null, "SYSTEM", "DISABLED"));
        createHandoff(conversation.getId(), message.getId(), "QUOTE_DRAFT_REQUEST_FAILED", false, channelMessage, intent, connection.getDefaultHandoffQueue(), hintsJson(rawText), riskFlags("QUOTE_REVIEW_REQUIRED"));
      }
    }
    createHandoff(conversation.getId(), message.getId(), "RFQ_REQUIRES_OPERATOR_REVIEW", false, channelMessage, intent, connection.getDefaultHandoffQueue(), hintsJson(rawText), riskFlags("QUOTE_REVIEW_REQUIRED"));
    return new BotWebhookAckResponse(conversation.getId(), message.getId(), intent, "RFQ_DRAFT_REQUIRES_HUMAN_REVIEW", quoteId == null ? "RFQ request received and routed to operator review. No quote, order, inventory, price, substitute, connector, or ERP action was executed." : "RFQ request received and draft quote review was requested. No quote, order, inventory, price, substitute, connector, or ERP action was executed.", true, rfq.getId());
  }

  private BotWebhookAckResponse substituteFlow(UUID tenantId, BotConversation conversation, BotMessage message, ChannelMessage channelMessage, String rawText, BotIntent intent, BotConnection connection, String flow) {
    ProductCatalogMatchResult match = matchProduct(tenantId, rawText, channelMessage.getCustomerAccountId());
    if (productSubstitutionService == null || match == null || match.productId() == null) {
      createHandoff(conversation.getId(), message.getId(), "SUBSTITUTE_REQUIRES_OPERATOR_REVIEW", false, channelMessage, intent, connection.getDefaultHandoffQueue(), hintsJson(rawText), riskFlags("SUBSTITUTE_UNAVAILABLE"));
      return new BotWebhookAckResponse(conversation.getId(), message.getId(), intent, "HUMAN_REVIEW", "I routed substitute options to an operator for validation.", true, null);
    }
    List<ProductSubstitutionService.SubstituteCandidate> candidates = productSubstitutionService.suggest(tenantId, match.productId(), match.normalizedCode(), rawText, channelMessage.getCustomerAccountId(), extractedQuantity(rawText));
    List<ProductSubstitutionService.SubstituteCandidate> visible = candidates.stream().filter(candidate -> !candidate.blocked()).limit(3).toList();
    auditEventService.record("BOT_SUBSTITUTE_SUGGESTED", "BOT_MESSAGE", message.getId().toString(), null, auditJson(connection.getId(), conversation.getId(), channelMessage.getId(), channelMessage.getCustomerAccountId(), intent, flow, visible.isEmpty() ? "NO_SAFE_SUBSTITUTE" : "SAFE_SUBSTITUTE_CANDIDATES", null, null, "BOT", "DISABLED"));
    if (visible.isEmpty() || visible.stream().anyMatch(ProductSubstitutionService.SubstituteCandidate::requiresApproval)) {
      createHandoff(conversation.getId(), message.getId(), visible.isEmpty() ? "NO_SAFE_SUBSTITUTE" : "HIGH_RISK_SUBSTITUTE_REQUIRES_APPROVAL", false, channelMessage, intent, connection.getDefaultHandoffQueue(), hintsJson(rawText), riskFlags("SUBSTITUTE_APPROVAL_REQUIRED"));
      return new BotWebhookAckResponse(conversation.getId(), message.getId(), intent, "HUMAN_REVIEW", "Substitute options need operator approval before selection.", true, null);
    }
    String options = String.join(", ", visible.stream().map(candidate -> candidate.sku() + " (" + candidate.reasonCode() + ")").toList());
    return respond(conversation, message, intent, "SUBSTITUTE_SUGGESTED", "Possible substitutes: " + options + ". Selection for a quote still requires the operator review command path.", false, connection, channelMessage, flow);
  }

  private BotWebhookAckResponse respond(BotConversation conversation, BotMessage message, BotIntent intent, String status, String response, boolean requiresReview, BotConnection connection, ChannelMessage channelMessage, String flow) {
    auditEventService.record("BOT_RESPONSE_SENT", "BOT_MESSAGE", message.getId().toString(), null, auditJson(connection.getId(), conversation.getId(), channelMessage.getId(), channelMessage.getCustomerAccountId(), intent, flow, status, null, null, "BOT", "DISABLED"));
    return new BotWebhookAckResponse(conversation.getId(), message.getId(), intent, status, response, requiresReview, null);
  }

  private BotHandoff createHandoff(UUID conversationId, UUID messageId, String reason, boolean updatePolicy) {
    return createHandoff(conversationId, messageId, reason, updatePolicy, null, null, null, null, null);
  }

  private BotHandoff createHandoff(UUID conversationId, UUID messageId, String reason, boolean updatePolicy, ChannelMessage channelMessage, BotIntent intent, String queue, String extractedHintsJson, String riskFlagsJson) {
    UUID tenantId = TenantContext.requireTenantId();
    BotConversation conversation = conversationRepository.findByIdAndTenantId(conversationId, tenantId).orElseThrow();
    BotMessage message = messageRepository.findByIdAndTenantId(messageId, tenantId).orElseThrow();
    if (!conversation.getId().equals(message.getConversationId())) {
      throw new IllegalArgumentException("Bot message does not belong to conversation");
    }
    String normalizedReason = reason == null || reason.isBlank() ? "OPERATOR_REVIEW_REQUESTED" : reason;
    List<BotHandoff> existingHandoffs = handoffRepository.findByTenantIdAndMessageIdOrderByCreatedAtDesc(tenantId, messageId);
    if (!existingHandoffs.isEmpty()) {
      return existingHandoffs.get(0);
    }
    BotHandoff handoff = handoffRepository.save(new BotHandoff(tenantId, conversationId, messageId, conversation.getChannel(), normalizedReason, clock.instant()));
    handoff.attachContext(
        channelMessage == null ? null : channelMessage.getId(),
        channelMessage == null ? null : channelMessage.getCustomerAccountId(),
        intent == null ? message.getDetectedIntent().name() : intent.name(),
        queue,
        extractedHintsJson,
        riskFlagsJson,
        clock.instant());
    handoff = handoffRepository.save(handoff);
    conversation.touch("HUMAN_REVIEW", true, clock.instant());
    if (updatePolicy) {
      conversation.applyPolicy(BotPolicyDecision.REQUIRE_HUMAN_HANDOFF.name(), "operator review", clock.instant());
    }
    conversationRepository.save(conversation);
    String metadata = jsonSupport.writeObject(Map.of(
        "conversationId", conversationId.toString(),
        "messageId", messageId.toString(),
        "channelMessageId", channelMessage == null ? "" : channelMessage.getId().toString(),
        "reason", safe(handoff.getReason()),
        "detectedIntent", safe(handoff.getDetectedIntent()),
        "assignedQueue", safe(handoff.getAssignedQueue()),
        "externalExecution", "DISABLED"));
    auditEventService.record("BOT_HUMAN_HANDOFF_CREATED", "BOT_HANDOFF", handoff.getId().toString(), null, metadata);
    auditEventService.record("BOT_HANDOFF_CREATED", "BOT_HANDOFF", handoff.getId().toString(), null, metadata);
    return handoff;
  }

  @Transactional
  public BotConversation markRequiresReview(UUID conversationId) {
    BotConversation conversation = getConversation(conversationId);
    conversation.touch("HUMAN_REVIEW", true, clock.instant());
    conversation.applyPolicy(BotPolicyDecision.REQUIRE_HUMAN_HANDOFF.name(), "mark needs review", clock.instant());
    BotConversation saved = conversationRepository.save(conversation);
    auditEventService.record("BOT_CONVERSATION_MARKED_NEEDS_REVIEW", "BOT_CONVERSATION", saved.getId().toString(), null, "{\"externalWrites\":\"DISABLED\"}");
    return saved;
  }

  @Transactional
  public BotConversation linkToReviewCase(UUID conversationId, UUID reviewCaseId) {
    UUID tenantId = TenantContext.requireTenantId();
    BotConversation conversation = conversationRepository.findByIdAndTenantId(conversationId, tenantId).orElseThrow();
    reviewCaseRepository.findByIdAndTenantId(reviewCaseId, tenantId).orElseThrow();
    conversation.linkReviewCase(reviewCaseId, clock.instant());
    conversation.touch("LINKED_TO_REVIEW", true, clock.instant());
    BotConversation saved = conversationRepository.save(conversation);
    auditEventService.record("BOT_CONVERSATION_LINKED_TO_REVIEW", "BOT_CONVERSATION", saved.getId().toString(), null, "{\"reviewCaseId\":\"" + reviewCaseId + "\"}");
    return saved;
  }

  private BotMessage saveMessageWithAudit(UUID tenantId, BotConversation conversation, ChannelMessage channelMessage, String channel, String chatId, String externalMessageId, String rawText, BotIntent intent, BotPolicyService.PolicyResult policy, BotConnection connection, String flow, boolean requiresReview, boolean simulation) {
    BotMessage message = messageRepository.save(new BotMessage(tenantId, conversation.getId(), channel, chatId, externalMessageId, rawText, intent, "RECEIVED", requiresReview, clock.instant()));
    auditEventService.record("BOT_MESSAGE_RECEIVED", "BOT_MESSAGE", message.getId().toString(), null, auditJson(connection.getId(), conversation.getId(), channelMessage.getId(), channelMessage.getCustomerAccountId(), intent, flow, "MESSAGE_RECEIVED", null, null, "BOT", "DISABLED"));
    auditEventService.record("BOT_INTENT_CLASSIFIED", "BOT_MESSAGE", message.getId().toString(), null, auditJson(connection.getId(), conversation.getId(), channelMessage.getId(), channelMessage.getCustomerAccountId(), intent, flow, "RULE_BASED_STAGE_12D", null, null, "SYSTEM", "DISABLED"));
    auditEventService.record("BOT_POLICY_DECISION_MADE", "BOT_CONVERSATION", conversation.getId().toString(), null, jsonSupport.writeObject(Map.of(
        "decision", policy.decision().name(),
        "reasonCode", safe(policy.reasonCode()),
        "suggestedNextAction", safe(policy.suggestedNextAction()),
        "flow", safe(flow),
        "simulation", simulation,
        "externalExecution", "DISABLED")));
    return message;
  }

  private BotWebhookAckResponse capturedWithHandoff(UUID tenantId, ChannelMessage channelMessage, String chatId, String externalMessageId, String rawText, BotIntent intent, String reason, String response, boolean requiresReview, BotConnection connection, List<String> risks) {
    Instant now = clock.instant();
    BotConversation conversation = conversationRepository.findByTenantIdAndChannelAndExternalChatId(tenantId, CHANNEL_TELEGRAM, chatId)
        .orElseGet(() -> conversationRepository.save(new BotConversation(tenantId, CHANNEL_TELEGRAM, chatId, now)));
    conversation.touch("HUMAN_REVIEW", true, now);
    conversation.applyPolicy(BotPolicyDecision.REQUIRE_HUMAN_HANDOFF.name(), reason, now);
    conversation = conversationRepository.save(conversation);
    BotMessage message = messageRepository.save(new BotMessage(tenantId, conversation.getId(), CHANNEL_TELEGRAM, chatId, externalMessageId, rawText, intent, "RECEIVED", true, now));
    createHandoff(conversation.getId(), message.getId(), reason, false, channelMessage, intent, connection.getDefaultHandoffQueue(), hintsJson(rawText), riskFlags(risks));
    return new BotWebhookAckResponse(conversation.getId(), message.getId(), intent, reason, response, requiresReview, null);
  }

  private BotConnection resolveConnection(UUID tenantId, String channel, Instant now) {
    BotConnection connection = connectionRepository.findByTenantIdAndChannelTypeForUpdate(tenantId, channel).stream().findFirst()
        .orElseGet(() -> connectionRepository.save(new BotConnection(tenantId, channel, null, null, true, allowedFlowsJson(DEFAULT_ALLOWED_FLOWS), "BOT_REVIEW", "{}", now)));
    connection.touch(now);
    return connectionRepository.save(connection);
  }

  private void requireSeededTenant(UUID tenantId) {
    if (!tenantRepository.existsById(tenantId)) {
      throw new NotFoundException("Tenant not found for X-Tenant-Id. Run scripts\\seed-local-demo.ps1 for the investor demo or use a seeded tenant id.");
    }
  }

  private BotPolicyService.PolicyResult policyFor(BotConnection connection, String flow, BotIntent intent, boolean knownCustomer) {
    if (!connection.isEnabled()) {
      return new BotPolicyService.PolicyResult(BotPolicyDecision.BLOCK_UNSUPPORTED, "BOT_DISABLED", true, "bot disabled; route to operator");
    }
    if (!allowedFlows(connection).contains(flow)) {
      return new BotPolicyService.PolicyResult(BotPolicyDecision.BLOCK_UNSUPPORTED, "FLOW_DISABLED_" + flow, true, "flow disabled by bot policy");
    }
    return policyService.decide(intent, knownCustomer);
  }

  private boolean rateLimited(UUID tenantId, String conversationKey, Instant now) {
    long count = rateLimitEventRepository.countByTenantIdAndConversationKeyAndCreatedAtAfter(tenantId, conversationKey, now.minus(Duration.ofMinutes(1)));
    rateLimitEventRepository.save(new BotRateLimitEvent(tenantId, conversationKey, count >= RATE_LIMIT_PER_MINUTE ? "BLOCKED" : "ACCEPTED", now));
    return count >= RATE_LIMIT_PER_MINUTE;
  }

  private ProductCatalogMatchResult matchProduct(UUID tenantId, String rawText, UUID customerAccountId) {
    String productHint = productHint(rawText);
    if (productCatalogMatchingService != null) {
      return productCatalogMatchingService.match(tenantId, productHint, rawText, customerAccountId);
    }
    Optional<Product> product = productRepository.findByTenantIdAndSkuAndDeletedAtIsNull(tenantId, productHint);
    return product.map(value -> new ProductCatalogMatchResult(ProductCatalogMatchingService.ProductMatchType.SKU_EXACT, value.getId(), productHint, value.getSku(), value.getName(), BigDecimal.ONE, List.of(value.getId()), false))
        .orElseGet(() -> new ProductCatalogMatchResult(ProductCatalogMatchingService.ProductMatchType.NO_MATCH, null, productHint, null, null, BigDecimal.ZERO, List.of(), true));
  }

  private String flowFor(BotIntent intent) {
    return switch (intent == null ? BotIntent.UNKNOWN : intent) {
      case GREETING -> "GREETING";
      case CHECK_AVAILABILITY, PRODUCT_AVAILABILITY_QUESTION -> "CHECK_AVAILABILITY";
      case CHECK_PRICE, PRICE_QUESTION -> "CHECK_PRICE";
      case REQUEST_QUOTE, RFQ_REQUEST -> "REQUEST_QUOTE";
      case SUGGEST_SUBSTITUTE, SUBSTITUTE_QUESTION -> "SUGGEST_SUBSTITUTE";
      case ORDER_OR_QUOTE_STATUS, ORDER_STATUS_QUESTION -> "ORDER_OR_QUOTE_STATUS";
      case HUMAN_HANDOFF, HUMAN_HELP_REQUEST -> "HUMAN_HANDOFF";
      case UNSUPPORTED_REQUEST_SAFE_REPLY, UNKNOWN -> "UNSUPPORTED_REQUEST_SAFE_REPLY";
    };
  }

  private TelegramMessage parse(JsonNode update) {
    JsonNode message = update == null ? null : update.path("message");
    JsonNode chat = message == null ? null : message.path("chat");
    if (message == null || message.isMissingNode() || chat == null || chat.isMissingNode()) {
      throw new IllegalArgumentException("Malformed Telegram update payload");
    }
    JsonNode chatId = chat.path("id");
    JsonNode messageId = message.path("message_id");
    JsonNode text = message.path("text");
    if (chatId.isMissingNode() || messageId.isMissingNode() || text.isMissingNode() || text.asText().isBlank()) {
      throw new IllegalArgumentException("Malformed Telegram update payload");
    }
    JsonNode updateId = update == null ? null : update.path("update_id");
    return new TelegramMessage(chatId.asText(), messageId.asText(), updateId == null || updateId.isMissingNode() ? null : updateId.asText(), text.asText().trim());
  }

  private String normalizeMinimalRequestText(String rawText) {
    return rawText == null ? null : rawText.trim().replaceAll("\\s+", " ");
  }

  private String safeResponse(BotIntent intent, BotPolicyService.PolicyResult policy) {
    return switch (policy.decision()) {
      case REQUIRE_CUSTOMER_IDENTIFICATION -> "I can route this to an operator after customer identity is verified. I cannot disclose price or order details here.";
      case BLOCK_UNSUPPORTED -> "I cannot handle that request automatically. I routed it to a human operator.";
      case REQUIRE_HUMAN_HANDOFF -> "I routed this conversation to a human operator.";
      case REQUIRE_OPERATOR_REVIEW -> intent == BotIntent.RFQ_REQUEST
          ? "I captured the RFQ request for operator review. No quote, order, inventory, price, substitute, connector, or ERP action was executed."
          : "I captured the request for operator review. I cannot answer with live business data or approve any action.";
      case ALLOW_DRAFT_RESPONSE -> "I can prepare a bounded response draft for operator-controlled handling without changing business records.";
      case ALLOW_SAFE_RESPONSE -> "I can provide a safe response without changing business records.";
    };
  }

  private BigDecimal extractedQuantity(String rawText) {
    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\b(\\d+(?:[.,]\\d+)?)\\b").matcher(rawText == null ? "" : rawText);
    return matcher.find() ? new BigDecimal(matcher.group(1).replace(',', '.')) : BigDecimal.ONE;
  }

  private String productHint(String rawText) {
    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)\\b([A-Z0-9][A-Z0-9._/-]{2,})\\b").matcher(rawText == null ? "" : rawText.toUpperCase(Locale.ROOT));
    return matcher.find() ? matcher.group(1) : normalizeMinimalRequestText(rawText);
  }

  private String hintsJson(String rawText) {
    return jsonSupport.writeObject(Map.of("productHint", safe(productHint(rawText)), "quantity", extractedQuantity(rawText).toPlainString(), "uom", "EA"));
  }

  private String riskFlags(String reason) {
    return riskFlags(reason == null ? List.of() : List.of(reason));
  }

  private String riskFlags(List<String> risks) {
    return jsonSupport.writeObject(risks == null ? List.of() : risks);
  }

  private List<String> allowedFlows(BotConnection connection) {
    String raw = connection.getAllowedFlows();
    if (raw == null || raw.isBlank() || "[]".equals(raw.trim())) return DEFAULT_ALLOWED_FLOWS;
    return DEFAULT_ALLOWED_FLOWS.stream().filter(raw::contains).toList();
  }

  private String allowedFlowsJson(List<String> flows) {
    List<String> values = flows == null || flows.isEmpty() ? DEFAULT_ALLOWED_FLOWS : flows;
    return "[\"" + String.join("\",\"", values.stream().map(this::normalizeFlow).filter(flow -> DEFAULT_ALLOWED_FLOWS.contains(flow)).distinct().toList()) + "\"]";
  }

  private String normalizeFlow(String flow) {
    return flow == null ? "" : flow.trim().toUpperCase(Locale.ROOT);
  }

  private String auditJson(UUID connectionId, UUID conversationId, UUID channelMessageId, UUID customerId, BotIntent intent, String flow, String reasonCode, UUID quoteId, UUID handoffId, String actorType, String externalExecution) {
    java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
    metadata.put("botConnectionId", id(connectionId));
    metadata.put("conversationId", id(conversationId));
    metadata.put("channelMessageId", id(channelMessageId));
    metadata.put("customerId", id(customerId));
    metadata.put("intent", intent == null ? "" : intent.name());
    metadata.put("flow", safe(flow));
    metadata.put("policyDecision", safe(reasonCode));
    metadata.put("quoteId", id(quoteId));
    metadata.put("handoffId", id(handoffId));
    metadata.put("reasonCode", safe(reasonCode));
    metadata.put("actorType", safe(actorType));
    metadata.put("externalExecution", safe(externalExecution));
    return jsonSupport.writeObject(metadata);
  }

  private static String id(UUID value) {
    return value == null ? "" : value.toString();
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }

  private record TelegramMessage(String chatId, String messageId, String updateId, String rawText) {}
}
