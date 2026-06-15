package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage12ADtos.ValidationIssue;
import com.orderpilot.api.dto.Stage12CDtos.*;
import com.orderpilot.application.services.workspace.QuoteConversionAttemptReviewQueryService;
import com.orderpilot.application.services.workspace.QuoteReviewService;
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

  public QuoteReviewController(
      QuoteReviewService service,
      QuoteConversionAttemptReviewQueryService conversionAttemptQueryService,
      RequestActorResolver actorResolver) {
    this.service = service;
    this.conversionAttemptQueryService = conversionAttemptQueryService;
    this.actorResolver = actorResolver;
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
  public QuoteReviewCommandResult resolveIssue(@PathVariable UUID quoteId, @PathVariable UUID issueId, @RequestBody(required = false) ResolveValidationIssueRequest request, HttpServletRequest http) {
    return service.resolveIssue(quoteId, issueId, command(request, http));
  }

  @PostMapping("/{quoteId}/issues/{issueId}/reject")
  public QuoteReviewCommandResult rejectIssueSuggestion(@PathVariable UUID quoteId, @PathVariable UUID issueId, @RequestBody(required = false) RejectValidationIssueSuggestionRequest request, HttpServletRequest http) {
    return service.rejectIssueSuggestion(quoteId, issueId, command(request, http));
  }

  @PostMapping("/{quoteId}/issues/{issueId}/apply-fix")
  public QuoteReviewCommandResult applyIssueFix(@PathVariable UUID quoteId, @PathVariable UUID issueId, @RequestBody ApplyValidationIssueFixRequest request, HttpServletRequest http) {
    return service.applyIssueFix(quoteId, issueId, command(request, http));
  }

  @PostMapping("/{quoteId}/issues/{issueId}/escalate")
  public QuoteReviewCommandResult escalateIssue(@PathVariable UUID quoteId, @PathVariable UUID issueId, @RequestBody(required = false) EscalateValidationIssueRequest request, HttpServletRequest http) {
    return service.escalateIssue(quoteId, issueId, command(request, http));
  }

  @PostMapping("/{quoteId}/customer")
  public QuoteReviewCommandResult correctCustomer(@PathVariable UUID quoteId, @RequestBody CorrectQuoteCustomerRequest request, HttpServletRequest http) {
    return service.correctCustomer(quoteId, command(request, http));
  }

  @PostMapping("/{quoteId}/lines/{lineId}/correct")
  public QuoteReviewCommandResult correctLine(@PathVariable UUID quoteId, @PathVariable UUID lineId, @RequestBody CorrectQuoteLineRequest request, HttpServletRequest http) {
    return service.correctLine(quoteId, lineId, command(request, http));
  }

  @PostMapping("/{quoteId}/lines/{lineId}/substitutes/select")
  public QuoteReviewCommandResult selectSubstitute(@PathVariable UUID quoteId, @PathVariable UUID lineId, @RequestBody QuoteLineSubstituteRequest request, HttpServletRequest http) {
    return service.selectSubstitute(quoteId, lineId, command(request, http));
  }

  @PostMapping("/{quoteId}/lines/{lineId}/substitutes/reject")
  public QuoteReviewCommandResult rejectSubstitute(@PathVariable UUID quoteId, @PathVariable UUID lineId, @RequestBody QuoteLineSubstituteRequest request, HttpServletRequest http) {
    return service.rejectSubstitute(quoteId, lineId, command(request, http));
  }

  private UUID trustedActor(HttpServletRequest http) {
    return actorResolver.resolveVerifiedActor(http, TenantContext.requireTenantId());
  }

  private ResolveValidationIssueCommand command(ResolveValidationIssueRequest request, HttpServletRequest http) {
    return new ResolveValidationIssueCommand(null, trustedActor(http), null, request == null ? null : request.reasonCode(), request == null ? null : request.note());
  }

  private RejectValidationIssueSuggestionCommand command(RejectValidationIssueSuggestionRequest request, HttpServletRequest http) {
    return new RejectValidationIssueSuggestionCommand(null, trustedActor(http), null, request == null ? null : request.reasonCode(), request == null ? null : request.note());
  }

  private ApplyValidationIssueFixCommand command(ApplyValidationIssueFixRequest request, HttpServletRequest http) {
    if (request == null) return null;
    return new ApplyValidationIssueFixCommand(null, trustedActor(http), null, request.fixType(), request.values(), request.reasonCode(), request.note());
  }

  private EscalateValidationIssueCommand command(EscalateValidationIssueRequest request, HttpServletRequest http) {
    return new EscalateValidationIssueCommand(null, trustedActor(http), null, request == null ? null : request.reasonCode(), request == null ? null : request.note());
  }

  private CorrectQuoteCustomerCommand command(CorrectQuoteCustomerRequest request, HttpServletRequest http) {
    if (request == null) return null;
    return new CorrectQuoteCustomerCommand(null, trustedActor(http), null, request.customerAccountId(), request.reasonCode(), request.note());
  }

  private CorrectQuoteLineCommand command(CorrectQuoteLineRequest request, HttpServletRequest http) {
    if (request == null) return null;
    return new CorrectQuoteLineCommand(null, trustedActor(http), null, request.quantity(), request.uom(), request.productId(), request.removeLine(), request.manualFollowUp(), request.reasonCode(), request.note());
  }

  private QuoteLineSubstituteCommand command(QuoteLineSubstituteRequest request, HttpServletRequest http) {
    if (request == null) return null;
    return new QuoteLineSubstituteCommand(null, trustedActor(http), null, request.substituteProductId(), request.reasonCode(), request.note());
  }
}
