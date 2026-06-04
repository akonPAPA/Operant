package com.orderpilot.application.services.bot;

import com.orderpilot.api.dto.Stage7Dtos.BotReviewHandoffResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.bot.*;
import com.orderpilot.domain.workspace.ExceptionCase;
import com.orderpilot.domain.workspace.ExceptionCaseRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BotReviewHandoffService {
  private static final String SOURCE_TYPE = "BOT_CONVERSATION";
  private final BotConversationRepository conversationRepository;
  private final BotMessageRepository messageRepository;
  private final BotRfqRequestRepository rfqRequestRepository;
  private final BotHandoffRepository handoffRepository;
  private final ExceptionCaseRepository exceptionCaseRepository;
  private final AuditEventService auditEventService;
  private final Clock clock;

  public BotReviewHandoffService(BotConversationRepository conversationRepository, BotMessageRepository messageRepository, BotRfqRequestRepository rfqRequestRepository, BotHandoffRepository handoffRepository, ExceptionCaseRepository exceptionCaseRepository, AuditEventService auditEventService, Clock clock) {
    this.conversationRepository = conversationRepository;
    this.messageRepository = messageRepository;
    this.rfqRequestRepository = rfqRequestRepository;
    this.handoffRepository = handoffRepository;
    this.exceptionCaseRepository = exceptionCaseRepository;
    this.auditEventService = auditEventService;
    this.clock = clock;
  }

  @Transactional
  public BotReviewHandoffResponse createOrGet(UUID conversationId) {
    UUID tenantId = TenantContext.requireTenantId();
    BotConversation conversation = conversationRepository.findByIdAndTenantId(conversationId, tenantId).orElseThrow();
    if (!conversation.isRequiresHumanReview() && conversation.getPolicyDecision() == null) {
      auditEventService.record("BOT_REVIEW_HANDOFF_BLOCKED", "BOT_CONVERSATION", conversation.getId().toString(), null, "{\"reason\":\"CONVERSATION_NOT_POLICY_GATED\"}");
      throw new IllegalStateException("Bot conversation is not ready for operator review handoff");
    }

    BotMessage message = latestMessage(tenantId, conversationId);
    BotRfqRequest rfq = latestRfq(tenantId, conversationId);
    String handoffReason = latestHandoffReason(tenantId, conversationId, message.getId());

    ExceptionCase reviewCase = resolveExistingReviewCase(tenantId, conversation);
    boolean reused = reviewCase != null;
    if (reviewCase == null) {
      reviewCase = exceptionCaseRepository.save(new ExceptionCase(
          tenantId,
          "BOT-" + clock.instant().toEpochMilli(),
          SOURCE_TYPE,
          conversation.getId(),
          null,
          null,
          null,
          title(message),
          "OPEN",
          priority(message),
          severity(message),
          summary(conversation, message, rfq, handoffReason),
          clock.instant()));
      auditEventService.record("BOT_REVIEW_HANDOFF_CREATED", "REVIEW_CASE", reviewCase.getId().toString(), null, metadata(conversation, message, rfq, handoffReason));
    } else {
      auditEventService.record("BOT_REVIEW_HANDOFF_REUSED", "REVIEW_CASE", reviewCase.getId().toString(), null, metadata(conversation, message, rfq, handoffReason));
    }

    conversation.linkReviewCase(reviewCase.getId(), clock.instant());
    conversation.touch("LINKED_TO_REVIEW", true, clock.instant());
    conversationRepository.save(conversation);
    auditEventService.record("BOT_CONVERSATION_LINKED_TO_REVIEW", "BOT_CONVERSATION", conversation.getId().toString(), null, "{\"reviewCaseId\":\"" + reviewCase.getId() + "\"}");
    return response(reviewCase, reused, conversation, message, rfq, handoffReason);
  }

  @Transactional(readOnly = true)
  public BotReviewHandoffResponse get(UUID conversationId) {
    UUID tenantId = TenantContext.requireTenantId();
    BotConversation conversation = conversationRepository.findByIdAndTenantId(conversationId, tenantId).orElseThrow();
    ExceptionCase reviewCase = resolveExistingReviewCase(tenantId, conversation);
    if (reviewCase == null) {
      throw new java.util.NoSuchElementException("Bot review handoff does not exist");
    }
    BotMessage message = latestMessage(tenantId, conversationId);
    BotRfqRequest rfq = latestRfq(tenantId, conversationId);
    return response(reviewCase, true, conversation, message, rfq, latestHandoffReason(tenantId, conversationId, message.getId()));
  }

  private ExceptionCase resolveExistingReviewCase(UUID tenantId, BotConversation conversation) {
    if (conversation.getLinkedReviewCaseId() != null) {
      return exceptionCaseRepository.findByIdAndTenantId(conversation.getLinkedReviewCaseId(), tenantId).orElse(null);
    }
    return exceptionCaseRepository.findFirstByTenantIdAndSourceTypeAndSourceIdOrderByCreatedAtDesc(tenantId, SOURCE_TYPE, conversation.getId()).orElse(null);
  }

  private BotMessage latestMessage(UUID tenantId, UUID conversationId) {
    return messageRepository.findByTenantIdAndConversationIdOrderByCreatedAtDesc(tenantId, conversationId).stream().findFirst().orElseThrow();
  }

  private BotRfqRequest latestRfq(UUID tenantId, UUID conversationId) {
    return rfqRequestRepository.findByTenantIdAndConversationIdOrderByCreatedAtDesc(tenantId, conversationId).stream().findFirst().orElse(null);
  }

  private String latestHandoffReason(UUID tenantId, UUID conversationId, UUID messageId) {
    return handoffRepository.findByTenantIdAndMessageIdOrderByCreatedAtDesc(tenantId, messageId).stream()
        .findFirst()
        .or(() -> handoffRepository.findByTenantIdAndConversationIdOrderByCreatedAtDesc(tenantId, conversationId).stream().findFirst())
        .map(BotHandoff::getReason)
        .orElse("BOT_POLICY_REQUIRES_OPERATOR_REVIEW");
  }

  private String title(BotMessage message) {
    return "Bot review handoff: " + message.getDetectedIntent();
  }

  private String priority(BotMessage message) {
    return switch (message.getDetectedIntent()) {
      case REQUEST_QUOTE, CHECK_PRICE, ORDER_OR_QUOTE_STATUS -> "HIGH";
      case CHECK_AVAILABILITY, SUGGEST_SUBSTITUTE -> "NORMAL";
      case GREETING, HUMAN_HANDOFF, UNSUPPORTED_REQUEST_SAFE_REPLY -> "NORMAL";
      case RFQ_REQUEST, PRICE_QUESTION, ORDER_STATUS_QUESTION -> "HIGH";
      case PRODUCT_AVAILABILITY_QUESTION, SUBSTITUTE_QUESTION -> "NORMAL";
      case HUMAN_HELP_REQUEST, UNKNOWN -> "NORMAL";
    };
  }

  private String severity(BotMessage message) {
    return message.getDetectedIntent() == BotIntent.UNKNOWN ? "WARNING" : "INFO";
  }

  private String summary(BotConversation conversation, BotMessage message, BotRfqRequest rfq, String handoffReason) {
    String normalized = rfq == null ? "" : "; normalizedRequest=\"" + escape(rfq.getNormalizedRequestText()) + "\"";
    return "Bot-originated operator review. channel=" + conversation.getChannel()
        + "; conversationId=" + conversation.getId()
        + "; messageId=" + message.getId()
        + "; intent=" + message.getDetectedIntent()
        + "; policyDecision=" + conversation.getPolicyDecision()
        + "; handoffReason=" + handoffReason
        + "; rawText=\"" + escape(message.getRawText()) + "\""
        + normalized;
  }

  private String metadata(BotConversation conversation, BotMessage message, BotRfqRequest rfq, String handoffReason) {
    return "{\"source\":\"BOT_CONVERSATION\",\"channel\":\"" + escape(conversation.getChannel()) + "\",\"conversationId\":\"" + conversation.getId() + "\",\"messageId\":\"" + message.getId() + "\",\"intent\":\"" + message.getDetectedIntent() + "\",\"policyDecision\":\"" + escape(conversation.getPolicyDecision()) + "\",\"handoffReason\":\"" + escape(handoffReason) + "\",\"rfqRequestId\":\"" + (rfq == null ? "" : rfq.getId()) + "\"}";
  }

  private List<String> nextActions(BotMessage message, BotRfqRequest rfq) {
    return switch (message.getDetectedIntent()) {
      case REQUEST_QUOTE -> rfq == null
          ? List.of("CREATE_MANUAL_RFQ_REVIEW", "OPERATOR_REPLY_DRAFT", "WAIT_FOR_CUSTOMER")
          : List.of("CREATE_MANUAL_RFQ_REVIEW", "OPERATOR_REPLY_DRAFT", "WAIT_FOR_CUSTOMER", "CLOSE_HANDOFF");
      case CHECK_PRICE, ORDER_OR_QUOTE_STATUS -> List.of("REQUEST_IDENTIFICATION", "OPERATOR_REPLY_DRAFT", "WAIT_FOR_CUSTOMER");
      case CHECK_AVAILABILITY, SUGGEST_SUBSTITUTE -> List.of("OPERATOR_REPLY_DRAFT", "CREATE_MANUAL_RFQ_REVIEW", "WAIT_FOR_CUSTOMER");
      case HUMAN_HANDOFF, GREETING, UNSUPPORTED_REQUEST_SAFE_REPLY -> List.of("OPERATOR_REPLY_DRAFT", "WAIT_FOR_CUSTOMER", "CLOSE_HANDOFF");
      case RFQ_REQUEST -> rfq == null
          ? List.of("CREATE_MANUAL_RFQ_REVIEW", "OPERATOR_REPLY_DRAFT", "WAIT_FOR_CUSTOMER")
          : List.of("CREATE_MANUAL_RFQ_REVIEW", "OPERATOR_REPLY_DRAFT", "WAIT_FOR_CUSTOMER", "CLOSE_HANDOFF");
      case PRICE_QUESTION, ORDER_STATUS_QUESTION -> List.of("REQUEST_IDENTIFICATION", "OPERATOR_REPLY_DRAFT", "WAIT_FOR_CUSTOMER");
      case PRODUCT_AVAILABILITY_QUESTION, SUBSTITUTE_QUESTION -> List.of("OPERATOR_REPLY_DRAFT", "CREATE_MANUAL_RFQ_REVIEW", "WAIT_FOR_CUSTOMER");
      case HUMAN_HELP_REQUEST -> List.of("OPERATOR_REPLY_DRAFT", "WAIT_FOR_CUSTOMER", "CLOSE_HANDOFF");
      case UNKNOWN -> List.of("OPERATOR_REPLY_DRAFT", "WAIT_FOR_CUSTOMER", "CLOSE_HANDOFF");
    };
  }

  private BotReviewHandoffResponse response(ExceptionCase reviewCase, boolean reused, BotConversation conversation, BotMessage message, BotRfqRequest rfq, String handoffReason) {
    return new BotReviewHandoffResponse(
        reviewCase.getId(),
        reviewCase.getCaseNumber(),
        reviewCase.getSourceType(),
        reviewCase.getSourceId(),
        conversation.getId(),
        reviewCase.getStatus(),
        reviewCase.getTitle(),
        reviewCase.getSummary(),
        reused,
        conversation.getId(),
        message.getId(),
        rfq == null ? null : rfq.getId(),
        message.getDetectedIntent().name(),
        conversation.getPolicyDecision(),
        message.getRawText(),
        handoffReason,
        nextActions(message, rfq));
  }

  private static String escape(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
