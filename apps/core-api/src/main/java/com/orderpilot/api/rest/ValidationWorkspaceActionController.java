package com.orderpilot.api.rest;

import com.orderpilot.application.services.workspace.*;
import com.orderpilot.domain.workspace.*;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/validations/runs")
public class ValidationWorkspaceActionController {
  private final ExceptionCaseService caseService; private final DraftQuoteService quoteService; private final DraftOrderService orderService;
  public ValidationWorkspaceActionController(ExceptionCaseService caseService, DraftQuoteService quoteService, DraftOrderService orderService){this.caseService=caseService;this.quoteService=quoteService;this.orderService=orderService;}
  @PostMapping("/{id}/create-exception-case") public ExceptionCase createCase(@PathVariable UUID id){return caseService.createFromValidation(id);}
  @PostMapping("/{id}/create-draft-quote") public DraftQuote createQuote(@PathVariable UUID id){return quoteService.createFromValidation(id);}
  @PostMapping("/{id}/create-draft-order") public DraftOrder createOrder(@PathVariable UUID id){return orderService.createFromValidation(id);}
}
