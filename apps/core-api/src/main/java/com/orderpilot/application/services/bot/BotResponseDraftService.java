package com.orderpilot.application.services.bot;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.bot.*;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BotResponseDraftService {
  private final BotConversationRepository conversationRepository;
  private final BotMessageRepository messageRepository;
  private final BotResponseDraftRepository responseDraftRepository;
  private final BotPolicyService policyService;
  private final BotOutboundTransport outboundTransport;
  private final AuditEventService auditEventService;
  private final Clock clock;

  public BotResponseDraftService(BotConversationRepository conversationRepository, BotMessageRepository messageRepository, BotResponseDraftRepository responseDraftRepository, BotPolicyService policyService, BotOutboundTransport outboundTransport, AuditEventService auditEventService, Clock clock) {
    this.conversationRepository = conversationRepository;
    this.messageRepository = messageRepository;
    this.responseDraftRepository = responseDraftRepository;
    this.policyService = policyService;
    this.outboundTransport = outboundTransport;
    this.auditEventService = auditEventService;
    this.clock = clock;
  }

  @Transactional
  public BotResponseDraft createDraft(UUID conversationId, UUID sourceMessageId, boolean knownCustomerIdentity) {
    UUID tenantId = TenantContext.requireTenantId();
    BotConversation conversation = conversationRepository.findByIdAndTenantId(conversationId, tenantId).orElseThrow();
    BotMessage message = resolveSourceMessage(tenantId, conversationId, sourceMessageId);
    if (!conversation.getId().equals(message.getConversationId())) {
      throw new IllegalArgumentException("Bot message does not belong to conversation");
    }

    BotPolicyService.PolicyResult policy = policyService.decideResponseDraft(message.getDetectedIntent(), knownCustomerIdentity);
    if (policy.decision() == BotPolicyDecision.BLOCK_UNSUPPORTED) {
      auditEventService.record("BOT_RESPONSE_BLOCKED", "BOT_MESSAGE", message.getId().toString(), null, "{\"reasonCode\":\"" + policy.reasonCode() + "\"}");
      throw new IllegalStateException("Bot response is blocked: " + policy.reasonCode());
    }

    BotResponseDraft draft = responseDraftRepository.save(new BotResponseDraft(
        tenantId,
        conversation.getId(),
        message.getId(),
        conversation.getChannel(),
        message.getDetectedIntent().name(),
        policy.decision().name(),
        template(message.getDetectedIntent(), policy),
        true,
        clock.instant()));
    conversation.touch("RESPONSE_DRAFTED", true, clock.instant());
    conversation.applyPolicy(policy.decision().name(), policy.suggestedNextAction(), clock.instant());
    conversationRepository.save(conversation);
    auditEventService.record("BOT_RESPONSE_DRAFT_CREATED", "BOT_RESPONSE_DRAFT", draft.getId().toString(), null, "{\"conversationId\":\"" + conversationId + "\",\"messageId\":\"" + message.getId() + "\",\"policyDecision\":\"" + policy.decision() + "\"}");
    if (policy.decision() == BotPolicyDecision.REQUIRE_HUMAN_HANDOFF || policy.decision() == BotPolicyDecision.REQUIRE_CUSTOMER_IDENTIFICATION) {
      auditEventService.record("BOT_RESPONSE_HANDOFF_REQUIRED", "BOT_RESPONSE_DRAFT", draft.getId().toString(), null, "{\"reasonCode\":\"" + policy.reasonCode() + "\"}");
    }
    return draft;
  }

  @Transactional(readOnly = true)
  public List<BotResponseDraft> listDrafts(UUID conversationId) {
    UUID tenantId = TenantContext.requireTenantId();
    conversationRepository.findByIdAndTenantId(conversationId, tenantId).orElseThrow();
    return responseDraftRepository.findByTenantIdAndConversationIdOrderByCreatedAtDesc(tenantId, conversationId);
  }

  @Transactional(readOnly = true)
  public BotResponseDraft getDraft(UUID responseId) {
    return responseDraftRepository.findByIdAndTenantId(responseId, TenantContext.requireTenantId()).orElseThrow();
  }

  @Transactional
  public BotResponseDraft markReady(UUID responseId, UUID reviewedBy) {
    BotResponseDraft draft = getDraft(responseId);
    if ("STUB_SENT".equals(draft.getStatus())) {
      throw new IllegalStateException("Bot response draft was already stub-sent");
    }
    draft.markReady(reviewedBy, clock.instant());
    BotResponseDraft saved = responseDraftRepository.save(draft);
    auditEventService.record("BOT_RESPONSE_DRAFT_MARKED_READY", "BOT_RESPONSE_DRAFT", saved.getId().toString(), reviewedBy, "{\"externalWrites\":\"DISABLED\"}");
    return saved;
  }

  @Transactional
  public BotResponseDraft stubSend(UUID responseId) {
    BotResponseDraft draft = getDraft(responseId);
    if (!"READY_FOR_STUB_SEND".equals(draft.getStatus())) {
      throw new IllegalStateException("Bot response draft must be marked ready before stub-send");
    }
    BotOutboundTransport.StubSendResult result = outboundTransport.stubSend(draft);
    draft.markStubSent(clock.instant());
    BotResponseDraft saved = responseDraftRepository.save(draft);
    auditEventService.record("BOT_RESPONSE_STUB_SENT", "BOT_RESPONSE_DRAFT", saved.getId().toString(), null, "{\"transport\":\"" + result.transport() + "\",\"status\":\"" + result.status() + "\",\"externalNetwork\":\"DISABLED\"}");
    return saved;
  }

  private BotMessage resolveSourceMessage(UUID tenantId, UUID conversationId, UUID sourceMessageId) {
    if (sourceMessageId != null) {
      return messageRepository.findByIdAndTenantId(sourceMessageId, tenantId).orElseThrow();
    }
    return messageRepository.findByTenantIdAndConversationIdOrderByCreatedAtDesc(tenantId, conversationId).stream().findFirst().orElseThrow();
  }

  private String template(BotIntent intent, BotPolicyService.PolicyResult policy) {
    return switch (intent) {
      case RFQ_REQUEST -> "We received your request and created an RFQ draft for operator review.";
      case PRODUCT_AVAILABILITY_QUESTION -> "We need an operator to confirm the exact product before checking availability.";
      case PRICE_QUESTION -> policy.decision() == BotPolicyDecision.REQUIRE_CUSTOMER_IDENTIFICATION
          ? "We need an operator to verify your customer identity before discussing price."
          : "An operator will review the request before any price information is shared.";
      case SUBSTITUTE_QUESTION -> "An operator will review substitute options before any recommendation is shared.";
      case ORDER_STATUS_QUESTION -> "We need an operator to verify your customer identity before discussing order status.";
      case HUMAN_HELP_REQUEST -> "An operator will review this conversation and follow up.";
      case UNKNOWN -> "This request cannot be handled automatically and needs human review.";
    };
  }
}
