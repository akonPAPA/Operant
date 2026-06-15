package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage12ADtos.CreateDraftQuoteFromRfqCommand;
import com.orderpilot.api.dto.Stage12ADtos.QuoteApprovalCommandResponse;
import com.orderpilot.api.dto.Stage12ADtos.QuoteApprovalDecisionCommand;
import com.orderpilot.api.dto.Stage12ADtos.QuoteApprovalStateResponse;
import com.orderpilot.api.dto.Stage12ADtos.QuoteTransactionResponse;
import com.orderpilot.api.dto.Stage12BDtos.QuoteSourceContextDto;
import com.orderpilot.application.services.workspace.ChannelToQuoteWiringService;
import com.orderpilot.application.services.workspace.QuoteApprovalStateMachineService;
import com.orderpilot.application.services.workspace.QuoteDraftService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.security.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/quotes")
public class QuoteTransactionController {
  private final QuoteDraftService quoteDraftService;
  private final QuoteApprovalStateMachineService approvalStateMachineService;
  private final ChannelToQuoteWiringService channelToQuoteWiringService;
  private final RequestActorResolver actorResolver;

  public QuoteTransactionController(QuoteDraftService quoteDraftService, QuoteApprovalStateMachineService approvalStateMachineService, ChannelToQuoteWiringService channelToQuoteWiringService, RequestActorResolver actorResolver) {
    this.quoteDraftService = quoteDraftService;
    this.approvalStateMachineService = approvalStateMachineService;
    this.channelToQuoteWiringService = channelToQuoteWiringService;
    this.actorResolver = actorResolver;
  }

  @PostMapping("/from-rfq")
  public QuoteTransactionResponse createFromRfq(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody CreateDraftQuoteFromRfqCommand command,
      HttpServletRequest http) {
    UUID tenantId = TenantContext.requireTenantId();
    UUID actorId = trustedActor(http, tenantId);
    CreateDraftQuoteFromRfqCommand safeCommand = new CreateDraftQuoteFromRfqCommand(
        command == null ? null : command.tenantId(),
        actorId,
        command == null ? null : command.actorRole(),
        command == null ? null : command.customerExternalRef(),
        command == null ? null : command.customerName(),
        command == null ? null : command.requestedItems(),
        command == null ? null : command.requestedLocation(),
        command == null ? null : command.requestedDiscountPercent(),
        firstNonBlank(idempotencyKey, command == null ? null : command.idempotencyKey()));
    return idempotencyService.execute(
        tenantId,
        actorId,
        safeCommand.idempotencyKey(),
        "QUOTE_CREATE_FROM_RFQ",
        "DRAFT_QUOTE",
        "",
        safeCommand,
        QuoteTransactionResponse.class,
        () -> quoteDraftService.createFromRfq(safeCommand));
  }

  @GetMapping("/{id}/transaction")
  public QuoteTransactionResponse getTransaction(@PathVariable UUID id) {
    return quoteDraftService.get(id);
  }

  @GetMapping("/{id}/source-context")
  public QuoteSourceContextDto getSourceContext(@PathVariable UUID id) {
    return channelToQuoteWiringService.sourceContext(id);
  }

  @GetMapping("/{id}/approval-state")
  public QuoteApprovalStateResponse getApprovalState(@PathVariable UUID id) {
    return approvalStateMachineService.getQuoteApprovalState(id);
  }

  @PostMapping("/{id}/approve")
  public QuoteApprovalCommandResponse approve(@PathVariable UUID id, @RequestBody(required = false) QuoteApprovalDecisionCommand command, HttpServletRequest http) {
    return approvalStateMachineService.approveQuote(id, withTrustedActor(command, http));
  }

  @PostMapping("/{id}/reject")
  public QuoteApprovalCommandResponse reject(@PathVariable UUID id, @RequestBody(required = false) QuoteApprovalDecisionCommand command, HttpServletRequest http) {
    return approvalStateMachineService.rejectQuote(id, withTrustedActor(command, http));
  }

  @PostMapping("/{id}/request-changes")
  public QuoteApprovalCommandResponse requestChanges(@PathVariable UUID id, @RequestBody(required = false) QuoteApprovalDecisionCommand command, HttpServletRequest http) {
    return approvalStateMachineService.requestQuoteChanges(id, withTrustedActor(command, http));
  }

  @PostMapping("/{id}/convert-to-internal-order")
  public QuoteApprovalCommandResponse convertToInternalOrder(@PathVariable UUID id, @RequestBody(required = false) QuoteApprovalDecisionCommand command, HttpServletRequest http) {
    return approvalStateMachineService.convertApprovedQuoteToInternalDraftOrder(id, withTrustedActor(command, http));
  }

  // OP-CAP-17F: the quote-approval decision actor is the audit identity recorded against an approve/
  // reject/request-changes/convert decision. It is resolved from the trusted (optionally signed) actor
  // context and overrides any body-supplied actorId, so a caller cannot forge who made the decision.
  // The body's actorId is ignored; the rest of the command (reason/comment/idempotencyKey/etc.) is
  // business intent and is preserved.
  private QuoteApprovalDecisionCommand withTrustedActor(QuoteApprovalDecisionCommand command, HttpServletRequest http) {
    UUID actorId = actorResolver.resolveVerifiedActor(http, TenantContext.getTenantId().orElse(null));
    if (command == null) {
      return new QuoteApprovalDecisionCommand(null, actorId, null, null, null, null, null);
    }
    return new QuoteApprovalDecisionCommand(command.tenantId(), actorId, command.actorRole(), command.approvalRequestId(), command.reason(), command.comment(), command.idempotencyKey());
  }
}
