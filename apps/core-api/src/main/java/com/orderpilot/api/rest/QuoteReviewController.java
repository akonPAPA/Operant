package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage12ADtos.ValidationIssue;
import com.orderpilot.api.dto.Stage12CDtos.*;
import com.orderpilot.application.services.workspace.QuoteConversionAttemptReviewQueryService;
import com.orderpilot.application.services.workspace.QuoteReviewService;
import com.orderpilot.common.idempotency.IdempotencyService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.security.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/quote-review")
public class QuoteReviewController {
  private final QuoteReviewService service;
  private final QuoteConversionAttemptReviewQueryService conversionAttemptQueryService;
  private final RequestActorResolver actorResolver;
  private final IdempotencyService idempotencyService;

  public QuoteReviewController(
      QuoteReviewService service,
      QuoteConversionAttemptReviewQueryService conversionAttemptQueryService,
      RequestActorResolver actorResolver,
      IdempotencyService idempotencyService) {
    this.service = service;
    this.conversionAttemptQueryService = conversionAttemptQueryService;
    this.actorResolver = actorResolver;
    this.idempotencyService = idempotencyService;
  }

  @GetMapping("/queue")
  public List<QuoteReviewQueueRow> queue(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String sourceType,
      @RequestParam(required = false) String channel,
      @RequestParam(required = false) String customer,
      @RequestParam(required = false) String issueType,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) Boolean reviewRequired,
      @RequestParam(required = false) UUID assignedTo,
      @RequestParam(required = false) Instant createdFrom,
      @RequestParam(required = false) Instant createdTo) {
    return service.queue(status, sourceType, channel, customer, issueType, severity, reviewRequired, assignedTo, createdFrom, createdTo);
  }

  @GetMapping("/{quoteId}")
  public QuoteReviewDetail detail(@PathVariable UUID quoteId) {
    return service.detail(quoteId);
  }

  @GetMapping("/conversion-attempts")
  public List<QuoteConversionAttemptReviewItem> conversionAttempts(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Boolean reviewRequired,
      @RequestParam(required = false) String reasonCode,
      @RequestParam(required = false) String sourceChannel,
      @RequestParam(required = false) Boolean draftQuoteLinked,
      @RequestParam(required = false) Instant createdFrom,
      @RequestParam(required = false) Instant createdTo) {
    return conversionAttemptQueryService.list(new QuoteConversionAttemptReviewFilter(status, reviewRequired, reasonCode, sourceChannel, draftQuoteLinked, createdFrom, createdTo));
  }

  @GetMapping("/conversion-attempts/{attemptId}")
  public QuoteConversionAttemptReviewDetail conversionAttemptDetail(@PathVariable UUID attemptId) {
    return conversionAttemptQueryService.detail(attemptId);
  }

  @GetMapping("/issues")
  public List<ValidationIssue> issues(
      @RequestParam(required = false) String issueType,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) UUID quoteId) {
    return service.issues(issueType, severity, quoteId);
  }

  @PostMapping("/{quoteId}/issues/{issueId}/resolve")
  public QuoteReviewCommandResult resolveIssue(@PathVariable UUID quoteId, @PathVariable UUID issueId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody(required = false) ResolveValidationIssueRequest request, HttpServletRequest http) {
    UUID actorId = trustedActor(http);
    ResolveValidationIssueCommand command = command(request, actorId);
    return execute(idempotencyKey, actorId, "QUOTE_REVIEW_ISSUE_RESOLVE", quoteId, issueId, command, () -> service.resolveIssue(quoteId, issueId, command));
  }

  @PostMapping("/{quoteId}/issues/{issueId}/reject")
  public QuoteReviewCommandResult rejectIssueSuggestion(@PathVariable UUID quoteId, @PathVariable UUID issueId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody(required = false) RejectValidationIssueSuggestionRequest request, HttpServletRequest http) {
    UUID actorId = trustedActor(http);
    RejectValidationIssueSuggestionCommand command = command(request, actorId);
    return execute(idempotencyKey, actorId, "QUOTE_REVIEW_ISSUE_REJECT", quoteId, issueId, command, () -> service.rejectIssueSuggestion(quoteId, issueId, command));
  }

  @PostMapping("/{quoteId}/issues/{issueId}/apply-fix")
  public QuoteReviewCommandResult applyIssueFix(@PathVariable UUID quoteId, @PathVariable UUID issueId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody ApplyValidationIssueFixRequest request, HttpServletRequest http) {
    UUID actorId = trustedActor(http);
    ApplyValidationIssueFixCommand command = command(request, actorId);
    return execute(idempotencyKey, actorId, "QUOTE_REVIEW_ISSUE_APPLY_FIX", quoteId, issueId, command, () -> service.applyIssueFix(quoteId, issueId, command));
  }

  @PostMapping("/{quoteId}/issues/{issueId}/escalate")
  public QuoteReviewCommandResult escalateIssue(@PathVariable UUID quoteId, @PathVariable UUID issueId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody(required = false) EscalateValidationIssueRequest request, HttpServletRequest http) {
    UUID actorId = trustedActor(http);
    EscalateValidationIssueCommand command = command(request, actorId);
    return execute(idempotencyKey, actorId, "QUOTE_REVIEW_ISSUE_ESCALATE", quoteId, issueId, command, () -> service.escalateIssue(quoteId, issueId, command));
  }

  @PostMapping("/{quoteId}/customer")
  public QuoteReviewCommandResult correctCustomer(@PathVariable UUID quoteId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody CorrectQuoteCustomerRequest request, HttpServletRequest http) {
    UUID actorId = trustedActor(http);
    CorrectQuoteCustomerCommand command = command(request, actorId);
    return execute(idempotencyKey, actorId, "QUOTE_REVIEW_CUSTOMER_CORRECT", quoteId, quoteId, command, () -> service.correctCustomer(quoteId, command));
  }

  @PostMapping("/{quoteId}/lines/{lineId}/correct")
  public QuoteReviewCommandResult correctLine(@PathVariable UUID quoteId, @PathVariable UUID lineId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody CorrectQuoteLineRequest request, HttpServletRequest http) {
    UUID actorId = trustedActor(http);
    CorrectQuoteLineCommand command = command(request, actorId);
    return execute(idempotencyKey, actorId, "QUOTE_REVIEW_LINE_CORRECT", quoteId, lineId, command, () -> service.correctLine(quoteId, lineId, command));
  }

  @PostMapping("/{quoteId}/lines/{lineId}/substitutes/select")
  public QuoteReviewCommandResult selectSubstitute(@PathVariable UUID quoteId, @PathVariable UUID lineId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody QuoteLineSubstituteRequest request, HttpServletRequest http) {
    UUID actorId = trustedActor(http);
    QuoteLineSubstituteCommand command = command(request, actorId);
    return execute(idempotencyKey, actorId, "QUOTE_REVIEW_SUBSTITUTE_SELECT", quoteId, lineId, command, () -> service.selectSubstitute(quoteId, lineId, command));
  }

  @PostMapping("/{quoteId}/lines/{lineId}/substitutes/reject")
  public QuoteReviewCommandResult rejectSubstitute(@PathVariable UUID quoteId, @PathVariable UUID lineId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody QuoteLineSubstituteRequest request, HttpServletRequest http) {
    UUID actorId = trustedActor(http);
    QuoteLineSubstituteCommand command = command(request, actorId);
    return execute(idempotencyKey, actorId, "QUOTE_REVIEW_SUBSTITUTE_REJECT", quoteId, lineId, command, () -> service.rejectSubstitute(quoteId, lineId, command));
  }

  private QuoteReviewCommandResult execute(String idempotencyKey, UUID actorId, String commandType, UUID quoteId, UUID targetId, Object command, java.util.function.Supplier<QuoteReviewCommandResult> action) {
    return idempotencyService.execute(
        TenantContext.requireTenantId(),
        actorId,
        idempotencyKey,
        commandType,
        "DRAFT_QUOTE",
        quoteId + ":" + targetId,
        command,
        QuoteReviewCommandResult.class,
        action);
  }

  private UUID trustedActor(HttpServletRequest http) {
    UUID actorId = actorResolver.resolveVerifiedActor(http, TenantContext.requireTenantId());
    return RequestActorResolver.SYSTEM_ACTOR.equals(actorId) ? null : actorId;
  }

  private ResolveValidationIssueCommand command(ResolveValidationIssueRequest request, UUID actorId) {
    return new ResolveValidationIssueCommand(null, actorId, null, request == null ? null : request.reasonCode(), request == null ? null : request.note());
  }

  private RejectValidationIssueSuggestionCommand command(RejectValidationIssueSuggestionRequest request, UUID actorId) {
    return new RejectValidationIssueSuggestionCommand(null, actorId, null, request == null ? null : request.reasonCode(), request == null ? null : request.note());
  }

  private ApplyValidationIssueFixCommand command(ApplyValidationIssueFixRequest request, UUID actorId) {
    if (request == null) return null;
    return new ApplyValidationIssueFixCommand(null, actorId, null, request.fixType(), request.values(), request.reasonCode(), request.note());
  }

  private EscalateValidationIssueCommand command(EscalateValidationIssueRequest request, UUID actorId) {
    return new EscalateValidationIssueCommand(null, actorId, null, request == null ? null : request.reasonCode(), request == null ? null : request.note());
  }

  private CorrectQuoteCustomerCommand command(CorrectQuoteCustomerRequest request, UUID actorId) {
    if (request == null) return null;
    return new CorrectQuoteCustomerCommand(null, actorId, null, request.customerAccountId(), request.reasonCode(), request.note());
  }

  private CorrectQuoteLineCommand command(CorrectQuoteLineRequest request, UUID actorId) {
    if (request == null) return null;
    return new CorrectQuoteLineCommand(null, actorId, null, request.quantity(), request.uom(), request.productId(), request.removeLine(), request.manualFollowUp(), request.reasonCode(), request.note());
  }

  private QuoteLineSubstituteCommand command(QuoteLineSubstituteRequest request, UUID actorId) {
    if (request == null) return null;
    return new QuoteLineSubstituteCommand(null, actorId, null, request.substituteProductId(), request.reasonCode(), request.note());
  }
}
