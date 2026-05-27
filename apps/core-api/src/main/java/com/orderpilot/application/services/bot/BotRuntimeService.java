package com.orderpilot.application.services.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.orderpilot.api.dto.Stage7Dtos.BotSimulateMessageRequest;
import com.orderpilot.api.dto.Stage7Dtos.BotSimulateMessageResponse;
import com.orderpilot.api.dto.Stage7Dtos.BotWebhookAckResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.channel.ChannelGatewayService;
import com.orderpilot.application.services.channel.ChannelType;
import com.orderpilot.application.services.channel.NormalizedInboundMessage;
import com.orderpilot.application.services.channel.WebhookVerificationMode;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.bot.*;
import com.orderpilot.domain.intake.ChannelMessage;
import com.orderpilot.domain.workspace.ExceptionCaseRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BotRuntimeService {
  private static final String CHANNEL_TELEGRAM = "TELEGRAM";
  private final BotConversationRepository conversationRepository;
  private final BotMessageRepository messageRepository;
  private final BotRfqRequestRepository rfqRequestRepository;
  private final BotHandoffRepository handoffRepository;
  private final RuleBasedBotIntentClassifier intentClassifier;
  private final BotPolicyService policyService;
  private final BotWebhookSecurityService webhookSecurityService;
  private final AuditEventService auditEventService;
  private final ChannelGatewayService channelGatewayService;
  private final ExceptionCaseRepository reviewCaseRepository;
  private final Clock clock;

  public BotRuntimeService(BotConversationRepository conversationRepository, BotMessageRepository messageRepository, BotRfqRequestRepository rfqRequestRepository, BotHandoffRepository handoffRepository, RuleBasedBotIntentClassifier intentClassifier, BotPolicyService policyService, BotWebhookSecurityService webhookSecurityService, AuditEventService auditEventService, ChannelGatewayService channelGatewayService, ExceptionCaseRepository reviewCaseRepository, Clock clock) {
    this.conversationRepository = conversationRepository;
    this.messageRepository = messageRepository;
    this.rfqRequestRepository = rfqRequestRepository;
    this.handoffRepository = handoffRepository;
    this.intentClassifier = intentClassifier;
    this.policyService = policyService;
    this.webhookSecurityService = webhookSecurityService;
    this.auditEventService = auditEventService;
    this.channelGatewayService = channelGatewayService;
    this.reviewCaseRepository = reviewCaseRepository;
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
    Instant now = clock.instant();
    ChannelMessage channelMessage = channelGatewayService.accept(new NormalizedInboundMessage(tenantId, ChannelType.TELEGRAM, incoming.chatId() + ":" + incoming.messageId(), incoming.chatId(), incoming.chatId(), "Telegram User", null, incoming.rawText(), List.of(), now, update.toString(), "TELEGRAM:" + incoming.chatId() + ":" + incoming.messageId()), verificationMode);
    BotIntent intent = intentClassifier.detect(incoming.rawText());
    BotPolicyService.PolicyResult policy = policyService.decide(intent, channelMessage.getCustomerAccountId() != null);
    boolean requiresReview = policy.requiresHumanReview();

    BotConversation conversation = conversationRepository.findByTenantIdAndChannelAndExternalChatId(tenantId, CHANNEL_TELEGRAM, incoming.chatId())
        .orElseGet(() -> conversationRepository.save(new BotConversation(tenantId, CHANNEL_TELEGRAM, incoming.chatId(), now)));
    conversation.touch(requiresReview ? "HUMAN_REVIEW" : "OPEN", requiresReview, now);
    conversation.applyPolicy(policy.decision().name(), policy.suggestedNextAction(), now);
    conversation = conversationRepository.save(conversation);

    webhookSecurityService.rejectReplay(tenantId, incoming.chatId(), incoming.messageId());

    BotMessage message = messageRepository.save(new BotMessage(tenantId, conversation.getId(), CHANNEL_TELEGRAM, incoming.chatId(), incoming.messageId(), incoming.rawText(), intent, "RECEIVED", requiresReview, now));
    auditEventService.record("BOT_MESSAGE_RECEIVED", "BOT_MESSAGE", message.getId().toString(), null, "{\"channel\":\"TELEGRAM\",\"intent\":\"" + intent + "\"}");
    auditEventService.record("BOT_INTENT_CLASSIFIED", "BOT_MESSAGE", message.getId().toString(), null, "{\"intent\":\"" + intent + "\",\"classifier\":\"RULE_BASED_STAGE_7\"}");
    auditEventService.record("BOT_POLICY_DECISION_MADE", "BOT_CONVERSATION", conversation.getId().toString(), null, "{\"decision\":\"" + policy.decision() + "\",\"reasonCode\":\"" + policy.reasonCode() + "\",\"suggestedNextAction\":\"" + policy.suggestedNextAction() + "\"}");

    if (intent == BotIntent.RFQ_REQUEST) {
      BotRfqRequest rfq = rfqRequestRepository.save(new BotRfqRequest(tenantId, conversation.getId(), message.getId(), CHANNEL_TELEGRAM, incoming.rawText(), normalizeMinimalRequestText(incoming.rawText()), now));
      auditEventService.record("BOT_RFQ_DRAFT_CREATED", "BOT_RFQ_REQUEST", rfq.getId().toString(), null, "{\"source\":\"TELEGRAM\",\"messageId\":\"" + message.getId() + "\"}");
      return new BotWebhookAckResponse(conversation.getId(), message.getId(), intent, "RFQ_DRAFT_REQUIRES_HUMAN_REVIEW", "RFQ request captured and routed to human review. No quote, order, ERP, inventory, price, or customer record was changed.", true, rfq.getId());
    }

    if (intent == BotIntent.UNKNOWN || intent == BotIntent.HUMAN_HELP_REQUEST) {
      BotHandoff handoff = createHandoff(conversation.getId(), message.getId(), "UNKNOWN".equals(intent.name()) ? "UNKNOWN_INTENT" : "HUMAN_HELP_REQUESTED", false);
      return new BotWebhookAckResponse(conversation.getId(), message.getId(), intent, "HUMAN_REVIEW", "Message routed to human review. No business records were changed.", true, null);
    }

    createHandoff(conversation.getId(), message.getId(), "INTENT_REQUIRES_OPERATOR_REVIEW", false);
    return new BotWebhookAckResponse(conversation.getId(), message.getId(), intent, "CAPTURED_REQUIRES_HUMAN_REVIEW", "Intent captured for operator review. Stage 7 does not execute availability, pricing, order, ERP, or master-data actions.", true, null);
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

    BotIntent intent = intentClassifier.detect(rawText);
    BotPolicyService.PolicyResult policy = policyService.decide(intent, request.knownCustomerIdentity());
    boolean requiresReview = policy.requiresHumanReview();

    BotConversation conversation = conversationRepository.findByTenantIdAndChannelAndExternalChatId(tenantId, channel, chatId)
        .orElseGet(() -> conversationRepository.save(new BotConversation(tenantId, channel, chatId, now)));
    conversation.touch(requiresReview ? "HUMAN_REVIEW" : "OPEN", requiresReview, now);
    conversation.applyPolicy(policy.decision().name(), policy.suggestedNextAction(), now);
    conversation = conversationRepository.save(conversation);

    if (messageRepository.existsByTenantIdAndChannelAndExternalChatIdAndExternalMessageId(tenantId, channel, chatId, messageId)) {
      throw new IllegalArgumentException("Bot message was already received");
    }

    channelGatewayService.accept(new NormalizedInboundMessage(tenantId, ChannelType.valueOf(channel), messageId, chatId, chatId, request.senderDisplayName(), null, rawText, List.of(), now, "{\"simulation\":true}", "BOT_SIM:" + channel + ":" + chatId + ":" + messageId));

    BotMessage message = messageRepository.save(new BotMessage(tenantId, conversation.getId(), channel, chatId, messageId, rawText, intent, "RECEIVED", requiresReview, now));
    auditEventService.record("BOT_MESSAGE_RECEIVED", "BOT_MESSAGE", message.getId().toString(), null, "{\"channel\":\"" + channel + "\",\"intent\":\"" + intent + "\",\"simulation\":true}");
    auditEventService.record("BOT_INTENT_CLASSIFIED", "BOT_MESSAGE", message.getId().toString(), null, "{\"intent\":\"" + intent + "\",\"classifier\":\"RULE_BASED_STAGE_7\",\"simulation\":true}");
    auditEventService.record("BOT_POLICY_DECISION_MADE", "BOT_CONVERSATION", conversation.getId().toString(), null, "{\"decision\":\"" + policy.decision() + "\",\"reasonCode\":\"" + policy.reasonCode() + "\",\"suggestedNextAction\":\"" + policy.suggestedNextAction() + "\",\"simulation\":true}");

    UUID rfqId = null;
    if (intent == BotIntent.RFQ_REQUEST) {
      BotRfqRequest rfq = rfqRequestRepository.save(new BotRfqRequest(tenantId, conversation.getId(), message.getId(), channel, rawText, normalizeMinimalRequestText(rawText), now));
      rfqId = rfq.getId();
      auditEventService.record("BOT_RFQ_DRAFT_CREATED", "BOT_RFQ_REQUEST", rfq.getId().toString(), null, "{\"source\":\"" + channel + "\",\"messageId\":\"" + message.getId() + "\",\"simulation\":true}");
    }

    if (policy.decision() == BotPolicyDecision.REQUIRE_HUMAN_HANDOFF
        || policy.decision() == BotPolicyDecision.BLOCK_UNSUPPORTED
        || policy.decision() == BotPolicyDecision.REQUIRE_CUSTOMER_IDENTIFICATION
        || intent != BotIntent.RFQ_REQUEST) {
      createHandoff(conversation.getId(), message.getId(), policy.reasonCode(), false);
    }

    return new BotSimulateMessageResponse(conversation.getId(), message.getId(), intent, policy.decision(), policy.reasonCode(), safeResponse(intent, policy), requiresReview, rfqId);
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

  private BotHandoff createHandoff(UUID conversationId, UUID messageId, String reason, boolean updatePolicy) {
    UUID tenantId = TenantContext.requireTenantId();
    BotConversation conversation = conversationRepository.findByIdAndTenantId(conversationId, tenantId).orElseThrow();
    BotMessage message = messageRepository.findByIdAndTenantId(messageId, tenantId).orElseThrow();
    if (!conversation.getId().equals(message.getConversationId())) {
      throw new IllegalArgumentException("Bot message does not belong to conversation");
    }
    BotHandoff handoff = handoffRepository.save(new BotHandoff(tenantId, conversationId, messageId, conversation.getChannel(), reason == null || reason.isBlank() ? "OPERATOR_REVIEW_REQUESTED" : reason, clock.instant()));
    conversation.touch("HUMAN_REVIEW", true, clock.instant());
    if (updatePolicy) {
      conversation.applyPolicy(BotPolicyDecision.REQUIRE_HUMAN_HANDOFF.name(), "operator review", clock.instant());
    }
    conversationRepository.save(conversation);
    auditEventService.record("BOT_HUMAN_HANDOFF_CREATED", "BOT_HANDOFF", handoff.getId().toString(), null, "{\"conversationId\":\"" + conversationId + "\",\"messageId\":\"" + messageId + "\",\"reason\":\"" + handoff.getReason() + "\"}");
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
    return new TelegramMessage(chatId.asText(), messageId.asText(), text.asText().trim());
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

  private record TelegramMessage(String chatId, String messageId, String rawText) {}
}
