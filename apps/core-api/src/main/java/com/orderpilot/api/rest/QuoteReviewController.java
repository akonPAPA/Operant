package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage12ADtos.ValidationIssue;
import com.orderpilot.api.dto.Stage12CDtos.*;
import com.orderpilot.application.services.workspace.QuoteConversionAttemptReviewQueryService;
import com.orderpilot.application.services.workspace.QuoteReviewService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/quote-review")
public class QuoteReviewController {
  private final QuoteReviewService service;
  private final QuoteConversionAttemptReviewQueryService conversionAttemptQueryService;

  public QuoteReviewController(QuoteReviewService service, QuoteConversionAttemptReviewQueryService conversionAttemptQueryService) {
    this.service = service;
    this.conversionAttemptQueryService = conversionAttemptQueryService;
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
  public QuoteReviewCommandResult resolveIssue(@PathVariable UUID quoteId, @PathVariable UUID issueId, @RequestBody(required = false) ResolveValidationIssueCommand command) {
    return service.resolveIssue(quoteId, issueId, command);
  }

  @PostMapping("/{quoteId}/issues/{issueId}/reject")
  public QuoteReviewCommandResult rejectIssueSuggestion(@PathVariable UUID quoteId, @PathVariable UUID issueId, @RequestBody(required = false) RejectValidationIssueSuggestionCommand command) {
    return service.rejectIssueSuggestion(quoteId, issueId, command);
  }

  @PostMapping("/{quoteId}/issues/{issueId}/apply-fix")
  public QuoteReviewCommandResult applyIssueFix(@PathVariable UUID quoteId, @PathVariable UUID issueId, @RequestBody ApplyValidationIssueFixCommand command) {
    return service.applyIssueFix(quoteId, issueId, command);
  }

  @PostMapping("/{quoteId}/issues/{issueId}/escalate")
  public QuoteReviewCommandResult escalateIssue(@PathVariable UUID quoteId, @PathVariable UUID issueId, @RequestBody(required = false) EscalateValidationIssueCommand command) {
    return service.escalateIssue(quoteId, issueId, command);
  }

  @PostMapping("/{quoteId}/customer")
  public QuoteReviewCommandResult correctCustomer(@PathVariable UUID quoteId, @RequestBody CorrectQuoteCustomerCommand command) {
    return service.correctCustomer(quoteId, command);
  }

  @PostMapping("/{quoteId}/lines/{lineId}/correct")
  public QuoteReviewCommandResult correctLine(@PathVariable UUID quoteId, @PathVariable UUID lineId, @RequestBody CorrectQuoteLineCommand command) {
    return service.correctLine(quoteId, lineId, command);
  }

  @PostMapping("/{quoteId}/lines/{lineId}/substitutes/select")
  public QuoteReviewCommandResult selectSubstitute(@PathVariable UUID quoteId, @PathVariable UUID lineId, @RequestBody QuoteLineSubstituteCommand command) {
    return service.selectSubstitute(quoteId, lineId, command);
  }

  @PostMapping("/{quoteId}/lines/{lineId}/substitutes/reject")
  public QuoteReviewCommandResult rejectSubstitute(@PathVariable UUID quoteId, @PathVariable UUID lineId, @RequestBody QuoteLineSubstituteCommand command) {
    return service.rejectSubstitute(quoteId, lineId, command);
  }
}
