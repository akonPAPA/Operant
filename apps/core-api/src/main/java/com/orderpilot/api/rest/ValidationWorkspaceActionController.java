package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage6Dtos.WorkspaceDraftOrderDto;
import com.orderpilot.api.dto.Stage6Dtos.WorkspaceDraftQuoteDto;
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
  @PostMapping("/{id}/create-draft-quote") public WorkspaceDraftQuoteDto createQuote(@PathVariable UUID id){return toDto(quoteService.createFromValidation(id));}
  @PostMapping("/{id}/create-draft-order") public WorkspaceDraftOrderDto createOrder(@PathVariable UUID id){return toDto(orderService.createFromValidation(id));}

  private static WorkspaceDraftQuoteDto toDto(DraftQuote q) {
    if (q == null) return null;
    return new WorkspaceDraftQuoteDto(q.getId(), q.getQuoteNumber(), q.getCustomerAccountId(), q.getCustomerDisplayName(), q.getStatus(), q.getValidationStatus(), q.isRequiresHumanReview(), q.getCurrency(), q.getSubtotalAmount(), q.getDiscountAmount(), q.getTotalAmount(), q.getMarginPercent(), q.getCreatedAt());
  }

  private static WorkspaceDraftOrderDto toDto(DraftOrder o) {
    if (o == null) return null;
    return new WorkspaceDraftOrderDto(o.getId(), o.getOrderNumber(), o.getCustomerAccountId(), o.getStatus(), o.getCurrency(), o.getSubtotalAmount(), o.getDiscountAmount(), o.getTotalAmount(), o.getMarginPercent(), o.getCreatedAt());
  }
}
