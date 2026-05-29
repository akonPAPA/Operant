package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage12ADtos.CreateDraftQuoteFromRfqCommand;
import com.orderpilot.api.dto.Stage12ADtos.QuoteApprovalCommandResponse;
import com.orderpilot.api.dto.Stage12ADtos.QuoteApprovalDecisionCommand;
import com.orderpilot.api.dto.Stage12ADtos.QuoteApprovalStateResponse;
import com.orderpilot.api.dto.Stage12ADtos.QuoteTransactionResponse;
import com.orderpilot.application.services.workspace.QuoteApprovalStateMachineService;
import com.orderpilot.application.services.workspace.QuoteDraftService;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/quotes")
public class QuoteTransactionController {
  private final QuoteDraftService quoteDraftService;
  private final QuoteApprovalStateMachineService approvalStateMachineService;

  public QuoteTransactionController(QuoteDraftService quoteDraftService, QuoteApprovalStateMachineService approvalStateMachineService) {
    this.quoteDraftService = quoteDraftService;
    this.approvalStateMachineService = approvalStateMachineService;
  }

  @PostMapping("/from-rfq")
  public QuoteTransactionResponse createFromRfq(@RequestBody CreateDraftQuoteFromRfqCommand command) {
    return quoteDraftService.createFromRfq(command);
  }

  @GetMapping("/{id}/transaction")
  public QuoteTransactionResponse getTransaction(@PathVariable UUID id) {
    return quoteDraftService.get(id);
  }

  @GetMapping("/{id}/approval-state")
  public QuoteApprovalStateResponse getApprovalState(@PathVariable UUID id) {
    return approvalStateMachineService.getQuoteApprovalState(id);
  }

  @PostMapping("/{id}/approve")
  public QuoteApprovalCommandResponse approve(@PathVariable UUID id, @RequestBody(required = false) QuoteApprovalDecisionCommand command) {
    return approvalStateMachineService.approveQuote(id, command);
  }

  @PostMapping("/{id}/reject")
  public QuoteApprovalCommandResponse reject(@PathVariable UUID id, @RequestBody(required = false) QuoteApprovalDecisionCommand command) {
    return approvalStateMachineService.rejectQuote(id, command);
  }

  @PostMapping("/{id}/request-changes")
  public QuoteApprovalCommandResponse requestChanges(@PathVariable UUID id, @RequestBody(required = false) QuoteApprovalDecisionCommand command) {
    return approvalStateMachineService.requestQuoteChanges(id, command);
  }

  @PostMapping("/{id}/convert-to-internal-order")
  public QuoteApprovalCommandResponse convertToInternalOrder(@PathVariable UUID id, @RequestBody(required = false) QuoteApprovalDecisionCommand command) {
    return approvalStateMachineService.convertApprovedQuoteToInternalDraftOrder(id, command);
  }
}
