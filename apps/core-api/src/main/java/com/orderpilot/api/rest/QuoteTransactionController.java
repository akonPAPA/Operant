package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage12ADtos.CreateDraftQuoteFromRfqCommand;
import com.orderpilot.api.dto.Stage12ADtos.QuoteTransactionResponse;
import com.orderpilot.application.services.workspace.QuoteDraftService;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/quotes")
public class QuoteTransactionController {
  private final QuoteDraftService quoteDraftService;

  public QuoteTransactionController(QuoteDraftService quoteDraftService) {
    this.quoteDraftService = quoteDraftService;
  }

  @PostMapping("/from-rfq")
  public QuoteTransactionResponse createFromRfq(@RequestBody CreateDraftQuoteFromRfqCommand command) {
    return quoteDraftService.createFromRfq(command);
  }

  @GetMapping("/{id}/transaction")
  public QuoteTransactionResponse getTransaction(@PathVariable UUID id) {
    return quoteDraftService.get(id);
  }
}
