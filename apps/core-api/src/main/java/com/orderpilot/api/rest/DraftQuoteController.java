package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage11ADtos.*;
import com.orderpilot.api.dto.Stage11EDtos.*;
import com.orderpilot.application.services.workspace.RfqToDraftQuoteService;
import com.orderpilot.application.services.workspace.QuoteExternalWritePreparationService;
import com.orderpilot.application.services.workspace.SubstituteApprovalService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/quotes/drafts")
public class DraftQuoteController {
  private final RfqToDraftQuoteService service;
  private final SubstituteApprovalService substituteApprovalService;
  private final QuoteExternalWritePreparationService externalWritePreparationService;

  public DraftQuoteController(RfqToDraftQuoteService service, SubstituteApprovalService substituteApprovalService, QuoteExternalWritePreparationService externalWritePreparationService) {
    this.service = service;
    this.substituteApprovalService = substituteApprovalService;
    this.externalWritePreparationService = externalWritePreparationService;
  }

  @PostMapping("/from-rfq")
  public DraftQuoteResponse createFromRfq(@RequestBody CreateDraftQuoteFromRfqRequest request) {
    return service.createFromRfq(request);
  }

  @GetMapping("/{id}")
  public DraftQuoteResponse get(@PathVariable UUID id) {
    return service.get(id);
  }

  @GetMapping
  public List<DraftQuoteResponse> list(@RequestParam(required = false) String status, @RequestParam(required = false) String sourceType) {
    return service.list(status, sourceType);
  }

  @PostMapping("/{id}/lines/{lineId}/substitute/approve")
  public DraftQuoteResponse approveSubstitute(@PathVariable UUID id, @PathVariable UUID lineId, @RequestBody SubstituteDecisionCommand command) {
    return substituteApprovalService.approveSubstitute(id, lineId, command);
  }

  @PostMapping("/{id}/lines/{lineId}/substitute/reject")
  public DraftQuoteResponse rejectSubstitute(@PathVariable UUID id, @PathVariable UUID lineId, @RequestBody SubstituteDecisionCommand command) {
    return substituteApprovalService.rejectSubstitute(id, lineId, command);
  }

  @PostMapping("/{id}/lines/{lineId}/substitute/reset")
  public DraftQuoteResponse resetSubstitute(@PathVariable UUID id, @PathVariable UUID lineId, @RequestBody(required = false) SubstituteDecisionCommand command) {
    return substituteApprovalService.resetSubstituteDecision(id, lineId, command);
  }

  @PostMapping("/{id}/mark-ready")
  public DraftQuoteResponse markReady(@PathVariable UUID id, @RequestBody QuoteLifecycleCommand command) {
    return substituteApprovalService.markReady(id, command);
  }

  @PostMapping("/{id}/approve-internal")
  public DraftQuoteResponse approveInternal(@PathVariable UUID id, @RequestBody QuoteLifecycleCommand command) {
    return substituteApprovalService.approveQuote(id, command);
  }

  @PostMapping("/{id}/reject")
  public DraftQuoteResponse rejectQuote(@PathVariable UUID id, @RequestBody QuoteLifecycleCommand command) {
    return substituteApprovalService.rejectQuote(id, command);
  }

  @PostMapping("/{id}/cancel")
  public DraftQuoteResponse cancelQuote(@PathVariable UUID id, @RequestBody QuoteLifecycleCommand command) {
    return substituteApprovalService.cancelQuote(id, command);
  }

  @PostMapping("/{id}/handoff/readiness")
  public QuoteHandoffResponse handoffReadiness(@PathVariable UUID id, @RequestBody(required = false) QuoteHandoffCommand command) {
    return externalWritePreparationService.checkReadiness(id, command);
  }

  @PostMapping("/{id}/handoff/prepare")
  public QuoteHandoffResponse prepareHandoff(@PathVariable UUID id, @RequestBody(required = false) QuoteHandoffCommand command) {
    return externalWritePreparationService.prepareSnapshot(id, command);
  }

  @PostMapping("/{id}/change-requests")
  public QuoteHandoffResponse createChangeRequestDraft(@PathVariable UUID id, @RequestBody(required = false) ChangeRequestDraftCommand command) {
    return externalWritePreparationService.createChangeRequestDraft(id, command);
  }
}
