package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage12ADtos.CreateDraftQuoteFromRfqCommand;
import com.orderpilot.api.dto.Stage12ADtos.CreateDraftQuoteFromRfqRequest;
import com.orderpilot.api.dto.Stage12ADtos.QuoteApprovalCommandResponse;
import com.orderpilot.api.dto.Stage12ADtos.QuoteApprovalDecisionCommand;
import com.orderpilot.api.dto.Stage12ADtos.QuoteApprovalDecisionRequest;
import com.orderpilot.api.dto.Stage12ADtos.QuoteApprovalStateResponse;
import com.orderpilot.api.dto.Stage12ADtos.QuoteTransactionResponse;
import com.orderpilot.api.dto.Stage12BDtos.QuoteSourceContextDto;
import com.orderpilot.application.services.workspace.ChannelToQuoteWiringService;
import com.orderpilot.application.services.workspace.QuoteApprovalStateMachineService;
import com.orderpilot.application.services.workspace.QuoteDraftService;
import com.orderpilot.common.idempotency.IdempotencyService;
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
  private final IdempotencyService idempotencyService;
  private final RequestActorResolver actorResolver;

  public QuoteTransactionController(
      QuoteDraftService quoteDraftService,
      QuoteApprovalStateMachineService approvalStateMachineService,
      ChannelToQuoteWiringService channelToQuoteWiringService,
      IdempotencyService idempotencyService,
      RequestActorResolver actorResolver) {
    this.quoteDraftService = quoteDraftService;
    this.approvalStateMachineService = approvalStateMachineService;
    this.channelToQuoteWiringService = channelToQuoteWiringService;
    this.idempotencyService = idempotencyService;
    this.actorResolver = actorResolver;
  }

  @PostMapping("/from-rfq")
  public QuoteTransactionResponse createFromRfq(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) CreateDraftQuoteFromRfqRequest request,
      HttpServletRequest http) {
    UUID tenantId = TenantContext.requireTenantId();
    UUID actorId = trustedActor(http, tenantId);
    // OP-CAP-31: tenant from TenantContext, actor from RequestActorResolver, idempotency key from
    // the header — never from the request body. The public request carries business intent only.
    CreateDraftQuoteFromRfqCommand safeCommand = new CreateDraftQuoteFromRfqCommand(
        tenantId,
        actorId,
        null,
        request == null ? null : request.customerExternalRef(),
        request == null ? null : request.customerName(),
        request == null ? null : request.requestedItems(),
        request == null ? null : request.requestedLocation(),
        request == null ? null : request.requestedDiscountPercent(),
        firstNonBlank(idempotencyKey));
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
  public QuoteApprovalCommandResponse approve(@PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody(required = false) QuoteApprovalDecisionRequest request, HttpServletRequest http) {
    QuoteApprovalDecisionCommand safeCommand = command(idempotencyKey, request, http);
    return idempotencyService.execute(TenantContext.requireTenantId(), safeCommand.actorId(), safeCommand.idempotencyKey(), "QUOTE_APPROVE", "DRAFT_QUOTE", id.toString(), safeCommand, QuoteApprovalCommandResponse.class, () -> approvalStateMachineService.approveQuote(id, safeCommand));
  }

  @PostMapping("/{id}/reject")
  public QuoteApprovalCommandResponse reject(@PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody(required = false) QuoteApprovalDecisionRequest request, HttpServletRequest http) {
    QuoteApprovalDecisionCommand safeCommand = command(idempotencyKey, request, http);
    return idempotencyService.execute(TenantContext.requireTenantId(), safeCommand.actorId(), safeCommand.idempotencyKey(), "QUOTE_REJECT", "DRAFT_QUOTE", id.toString(), safeCommand, QuoteApprovalCommandResponse.class, () -> approvalStateMachineService.rejectQuote(id, safeCommand));
  }

  @PostMapping("/{id}/request-changes")
  public QuoteApprovalCommandResponse requestChanges(@PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody(required = false) QuoteApprovalDecisionRequest request, HttpServletRequest http) {
    QuoteApprovalDecisionCommand safeCommand = command(idempotencyKey, request, http);
    return idempotencyService.execute(TenantContext.requireTenantId(), safeCommand.actorId(), safeCommand.idempotencyKey(), "QUOTE_REQUEST_CHANGES", "DRAFT_QUOTE", id.toString(), safeCommand, QuoteApprovalCommandResponse.class, () -> approvalStateMachineService.requestQuoteChanges(id, safeCommand));
  }

  @PostMapping("/{id}/convert-to-internal-order")
  public QuoteApprovalCommandResponse convertToInternalOrder(@PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody(required = false) QuoteApprovalDecisionRequest request, HttpServletRequest http) {
    QuoteApprovalDecisionCommand safeCommand = command(idempotencyKey, request, http);
    return idempotencyService.execute(TenantContext.requireTenantId(), safeCommand.actorId(), safeCommand.idempotencyKey(), "QUOTE_CONVERT_TO_INTERNAL_ORDER", "DRAFT_QUOTE", id.toString(), safeCommand, QuoteApprovalCommandResponse.class, () -> approvalStateMachineService.convertApprovedQuoteToInternalDraftOrder(id, safeCommand));
  }

  private QuoteApprovalDecisionCommand command(String idempotencyKey, QuoteApprovalDecisionRequest request, HttpServletRequest http) {
    UUID tenantId = TenantContext.requireTenantId();
    UUID actorId = trustedActor(http, tenantId);
    // OP-CAP-31: tenant from context, actor from trusted resolver, idempotency key from header.
    return new QuoteApprovalDecisionCommand(
        tenantId,
        actorId,
        null,
        request == null ? null : request.approvalRequestId(),
        request == null ? null : request.reason(),
        request == null ? null : request.comment(),
        firstNonBlank(idempotencyKey));
  }

  private UUID trustedActor(HttpServletRequest http, UUID tenantId) {
    UUID actorId = actorResolver.resolveVerifiedActor(http, tenantId);
    return RequestActorResolver.SYSTEM_ACTOR.equals(actorId) ? null : actorId;
  }

  private static String firstNonBlank(String... values) {
    if (values == null) return null;
    for (String value : values) {
      if (value != null && !value.isBlank()) return value.trim();
    }
    return null;
  }
}
