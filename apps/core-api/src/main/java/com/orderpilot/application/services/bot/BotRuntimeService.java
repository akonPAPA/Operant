package com.orderpilot.application.services.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.orderpilot.api.dto.Stage7Dtos.BotWebhookAckResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.channel.ChannelGatewayService;
import com.orderpilot.application.services.channel.ChannelType;
import com.orderpilot.application.services.channel.NormalizedInboundMessage;
import com.orderpilot.application.services.channel.WebhookVerificationMode;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.bot.*;
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
  private final BotWebhookSecurityService webhookSecurityService;
  private final AuditEventService auditEventService;
  private final ChannelGatewayService channelGatewayService;
  private final Clock clock;

  public BotRuntimeService(BotConversationRepository conversationRepository, BotMessageRepository messageRepository, BotRfqRequestRepository rfqRequestRepository, BotHandoffRepository handoffRepository, RuleBasedBotIntentClassifier intentClassifier, BotWebhookSecurityService webhookSecurityService, AuditEventService auditEventService, ChannelGatewayService channelGatewayService, Clock clock) {
    this.conversationRepository = conversationRepository;
    this.messageRepository = messageRepository;
    this.rfqRequestRepository = rfqRequestRepository;
    this.handoffRepository = handoffRepository;
    this.intentClassifier = intentClassifier;
    this.webhookSecurityService = webhookSecurityService;
    this.auditEventService = auditEventService;
    this.channelGatewayService = channelGatewayService;
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
    channelGatewayService.accept(new NormalizedInboundMessage(tenantId, ChannelType.TELEGRAM, incoming.chatId() + ":" + incoming.messageId(), incoming.chatId(), incoming.chatId(), "Telegram User", null, incoming.rawText(), List.of(), now, update.toString(), "TELEGRAM:" + incoming.chatId() + ":" + incoming.messageId()), verificationMode);
    BotIntent intent = intentClassifier.detect(incoming.rawText());
    boolean requiresReview = true;

    BotConversation conversation = conversationRepository.findByTenantIdAndChannelAndExternalChatId(tenantId, CHANNEL_TELEGRAM, incoming.chatId())
        .orElseGet(() -> conversationRepository.save(new BotConversation(tenantId, CHANNEL_TELEGRAM, incoming.chatId(), now)));
    conversation.touch(requiresReview ? "HUMAN_REVIEW" : "OPEN", requiresReview, now);
    conversation = conversationRepository.save(conversation);

    webhookSecurityService.rejectReplay(tenantId, incoming.chatId(), incoming.messageId());

    BotMessage message = messageRepository.save(new BotMessage(tenantId, conversation.getId(), CHANNEL_TELEGRAM, incoming.chatId(), incoming.messageId(), incoming.rawText(), intent, "RECEIVED", requiresReview, now));
    auditEventService.record("BOT_MESSAGE_RECEIVED", "BOT_MESSAGE", message.getId().toString(), null, "{\"channel\":\"TELEGRAM\",\"intent\":\"" + intent + "\"}");

    if (intent == BotIntent.RFQ_REQUEST) {
      BotRfqRequest rfq = rfqRequestRepository.save(new BotRfqRequest(tenantId, conversation.getId(), message.getId(), CHANNEL_TELEGRAM, incoming.rawText(), normalizeMinimalRequestText(incoming.rawText()), now));
      auditEventService.record("BOT_RFQ_DRAFT_CREATED", "BOT_RFQ_REQUEST", rfq.getId().toString(), null, "{\"source\":\"TELEGRAM\",\"messageId\":\"" + message.getId() + "\"}");
      return new BotWebhookAckResponse(conversation.getId(), message.getId(), intent, "RFQ_DRAFT_REQUIRES_HUMAN_REVIEW", "RFQ request captured and routed to human review. No quote, order, ERP, inventory, price, or customer record was changed.", true, rfq.getId());
    }

    if (intent == BotIntent.UNKNOWN || intent == BotIntent.HUMAN_HANDOFF) {
      BotHandoff handoff = handoffRepository.save(new BotHandoff(tenantId, conversation.getId(), message.getId(), CHANNEL_TELEGRAM, "UNKNOWN_INTENT", now));
      auditEventService.record("BOT_HUMAN_HANDOFF_CREATED", "BOT_HANDOFF", handoff.getId().toString(), null, "{\"source\":\"TELEGRAM\",\"messageId\":\"" + message.getId() + "\"}");
      return new BotWebhookAckResponse(conversation.getId(), message.getId(), intent, "HUMAN_REVIEW", "Message routed to human review. No business records were changed.", true, null);
    }

    BotHandoff handoff = handoffRepository.save(new BotHandoff(tenantId, conversation.getId(), message.getId(), CHANNEL_TELEGRAM, "INTENT_NOT_SUPPORTED_IN_STAGE_7", now));
    auditEventService.record("BOT_HUMAN_HANDOFF_CREATED", "BOT_HANDOFF", handoff.getId().toString(), null, "{\"source\":\"TELEGRAM\",\"intent\":\"" + intent + "\",\"messageId\":\"" + message.getId() + "\"}");
    return new BotWebhookAckResponse(conversation.getId(), message.getId(), intent, "CAPTURED_REQUIRES_HUMAN_REVIEW", "Intent captured for operator review. Stage 7 does not execute availability, pricing, order, ERP, or master-data actions.", true, null);
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

  private record TelegramMessage(String chatId, String messageId, String rawText) {}
}
