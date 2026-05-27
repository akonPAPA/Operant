package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage7Dtos.*;
import com.orderpilot.application.services.bot.BotResponseDraftService;
import com.orderpilot.application.services.bot.BotReviewHandoffService;
import com.orderpilot.application.services.bot.BotRuntimeService;
import com.orderpilot.domain.bot.BotConversation;
import com.orderpilot.domain.bot.BotHandoff;
import com.orderpilot.domain.bot.BotMessage;
import com.orderpilot.domain.bot.BotResponseDraft;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/v1/bot-runtime", "/api/v1/bot/runtime"})
public class BotRuntimeController {
  private final BotRuntimeService service;
  private final BotResponseDraftService responseDraftService;
  private final BotReviewHandoffService reviewHandoffService;

  public BotRuntimeController(BotRuntimeService service, BotResponseDraftService responseDraftService, BotReviewHandoffService reviewHandoffService) {
    this.service = service;
    this.responseDraftService = responseDraftService;
    this.reviewHandoffService = reviewHandoffService;
  }

  @PostMapping("/messages/simulate")
  public BotSimulateMessageResponse simulate(@RequestBody BotSimulateMessageRequest request) {
    return service.simulate(request);
  }

  @GetMapping("/conversations")
  public List<BotConversationResponse> conversations() {
    return service.listConversations().stream().map(this::conversation).toList();
  }

  @GetMapping("/conversations/{id}")
  public BotConversationDetail conversationDetail(@PathVariable UUID id) {
    BotConversation conversation = service.getConversation(id);
    return new BotConversationDetail(
        conversation(conversation),
        service.listMessages(id).stream().map(this::message).toList(),
        service.listHandoffs(id).stream().map(this::handoff).toList(),
        responseDraftService.listDrafts(id).stream().map(this::responseDraft).toList());
  }

  @GetMapping("/messages/{id}")
  public BotMessageResponse messageDetail(@PathVariable UUID id) {
    return message(service.getMessage(id));
  }

  @PostMapping({"/conversations/{id}/handoff", "/conversations/{id}/handoffs"})
  public BotHandoffResponse createHandoff(@PathVariable UUID id, @RequestBody CreateHandoffRequest request) {
    return handoff(service.createHandoff(id, request.messageId(), request.reason()));
  }

  @PostMapping("/conversations/{id}/review-handoff")
  public BotReviewHandoffResponse createReviewHandoff(@PathVariable UUID id) {
    return reviewHandoffService.createOrGet(id);
  }

  @GetMapping("/conversations/{id}/review-handoff")
  public BotReviewHandoffResponse reviewHandoff(@PathVariable UUID id) {
    return reviewHandoffService.get(id);
  }

  @PostMapping("/conversations/{id}/needs-review")
  public BotConversationResponse markNeedsReview(@PathVariable UUID id) {
    return conversation(service.markRequiresReview(id));
  }

  @PostMapping("/conversations/{id}/link-review-case")
  public BotConversationResponse linkReviewCase(@PathVariable UUID id, @RequestBody LinkReviewCaseRequest request) {
    return conversation(service.linkToReviewCase(id, request.reviewCaseId()));
  }

  @PostMapping("/conversations/{id}/responses/draft")
  public BotResponseDraftResponse createResponseDraft(@PathVariable UUID id, @RequestBody(required = false) CreateBotResponseDraftRequest request) {
    UUID sourceMessageId = request == null ? null : request.sourceMessageId();
    boolean knownCustomerIdentity = request != null && request.knownCustomerIdentity();
    return responseDraft(responseDraftService.createDraft(id, sourceMessageId, knownCustomerIdentity));
  }

  @GetMapping("/conversations/{id}/responses")
  public List<BotResponseDraftResponse> responseDrafts(@PathVariable UUID id) {
    return responseDraftService.listDrafts(id).stream().map(this::responseDraft).toList();
  }

  @PostMapping("/responses/{id}/mark-ready")
  public BotResponseDraftResponse markResponseReady(@PathVariable UUID id, @RequestBody(required = false) MarkBotResponseReadyRequest request) {
    UUID reviewedBy = request == null ? null : request.reviewedBy();
    return responseDraft(responseDraftService.markReady(id, reviewedBy));
  }

  @PostMapping("/responses/{id}/stub-send")
  public BotResponseDraftResponse stubSendResponse(@PathVariable UUID id) {
    return responseDraft(responseDraftService.stubSend(id));
  }

  private BotConversationResponse conversation(BotConversation conversation) {
    return new BotConversationResponse(conversation.getId(), conversation.getChannel(), conversation.getExternalChatId(), conversation.getStatus(), conversation.isRequiresHumanReview(), conversation.getLinkedReviewCaseId(), conversation.getPolicyDecision(), conversation.getSuggestedNextAction(), conversation.getCreatedAt(), conversation.getUpdatedAt());
  }

  private BotMessageResponse message(BotMessage message) {
    return new BotMessageResponse(message.getId(), message.getConversationId(), message.getChannel(), message.getExternalChatId(), message.getExternalMessageId(), message.getRawText(), message.getDetectedIntent(), message.getStatus(), message.isRequiresHumanReview(), message.getCreatedAt());
  }

  private BotHandoffResponse handoff(BotHandoff handoff) {
    return new BotHandoffResponse(handoff.getId(), handoff.getConversationId(), handoff.getMessageId(), handoff.getChannel(), handoff.getReason(), handoff.getStatus(), handoff.isRequiresHumanReview());
  }

  private BotResponseDraftResponse responseDraft(BotResponseDraft draft) {
    return new BotResponseDraftResponse(draft.getId(), draft.getConversationId(), draft.getSourceMessageId(), draft.getChannel(), draft.getResponseType(), draft.getPolicyDecision(), draft.getStatus(), draft.getResponseText(), draft.isRequiresOperatorReview(), draft.getReviewedBy(), draft.getReviewedAt(), draft.getStubSentAt(), draft.getCreatedAt(), draft.getUpdatedAt());
  }
}
