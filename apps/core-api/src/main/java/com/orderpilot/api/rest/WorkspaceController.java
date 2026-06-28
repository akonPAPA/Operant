package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage6Dtos.*;
import com.orderpilot.application.services.workspace.*;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.workspace.*;
import com.orderpilot.security.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspace")
public class WorkspaceController {
  private final ExceptionCaseService caseService; private final SuggestedFixService fixService; private final DraftQuoteService quoteService; private final DraftOrderService orderService; private final DraftReviewService draftReviewService; private final ApprovalWorkflowService approvalService; private final WorkspaceTimelineService timelineService; private final WorkspaceNoteService noteService; private final WorkspaceSummaryService summaryService; private final RequestActorResolver actorResolver;
  public WorkspaceController(ExceptionCaseService caseService, SuggestedFixService fixService, DraftQuoteService quoteService, DraftOrderService orderService, DraftReviewService draftReviewService, ApprovalWorkflowService approvalService, WorkspaceTimelineService timelineService, WorkspaceNoteService noteService, WorkspaceSummaryService summaryService, RequestActorResolver actorResolver){this.caseService=caseService;this.fixService=fixService;this.quoteService=quoteService;this.orderService=orderService;this.draftReviewService=draftReviewService;this.approvalService=approvalService;this.timelineService=timelineService;this.noteService=noteService;this.summaryService=summaryService;this.actorResolver=actorResolver;}

  // Wave 01A — safe DTO mappers. Public/default responses must not return JPA/domain entities (no tenant/actor/audit/source/idempotency leaks).
  private static WorkspaceNoteDto toDto(WorkspaceNote n) {
    if (n == null) return null;
    return new WorkspaceNoteDto(n.getId(), n.getTargetType(), n.getTargetId(), n.getNoteText(), n.getCreatedAt());
  }

  private static WorkspaceDraftQuoteDto toDto(DraftQuote q) {
    if (q == null) return null;
    return new WorkspaceDraftQuoteDto(q.getId(), q.getQuoteNumber(), q.getCustomerAccountId(), q.getCustomerDisplayName(), q.getStatus(), q.getValidationStatus(), q.isRequiresHumanReview(), q.getCurrency(), q.getSubtotalAmount(), q.getDiscountAmount(), q.getTotalAmount(), q.getMarginPercent(), q.getCreatedAt());
  }

  private static WorkspaceDraftQuoteLineDto toDto(DraftQuoteLine l) {
    if (l == null) return null;
    return new WorkspaceDraftQuoteLineDto(l.getId(), l.getLineNumber(), l.getProductId(), l.getProductName(), l.getDescription(), l.getQuantity(), l.getUom(), l.getUnitPrice(), l.getDiscountPercent(), l.getLineTotal(), l.getMarginPercent(), l.getStatus(), l.getValidationStatus());
  }

  private static WorkspaceDraftOrderDto toDto(DraftOrder o) {
    if (o == null) return null;
    return new WorkspaceDraftOrderDto(o.getId(), o.getOrderNumber(), o.getCustomerAccountId(), o.getStatus(), o.getCurrency(), o.getSubtotalAmount(), o.getDiscountAmount(), o.getTotalAmount(), o.getMarginPercent(), o.getCreatedAt());
  }

  private static WorkspaceDraftOrderLineDto toDto(DraftOrderLine l) {
    if (l == null) return null;
    return new WorkspaceDraftOrderLineDto(l.getId(), l.getLineNumber(), l.getProductId(), l.getDescription(), l.getQuantity(), l.getUom(), l.getUnitPrice(), l.getDiscountPercent(), l.getLineTotal(), l.getMarginPercent(), l.getStatus(), l.getValidationStatus());
  }

  @PostMapping("/exception-cases/from-validation/{validationRunId}") public ExceptionCase createCase(@PathVariable UUID validationRunId){return caseService.createFromValidation(validationRunId);}
  @GetMapping("/exception-cases") public List<ExceptionCase> cases(){return caseService.list();}
  @GetMapping("/exception-cases/{id}") public ExceptionCase exceptionCase(@PathVariable UUID id){return caseService.get(id);}
  @GetMapping("/exception-cases/{id}/issues") public List<ExceptionCaseIssue> caseIssues(@PathVariable UUID id){return caseService.issues(id);}
  @PostMapping("/exception-cases/{id}/assign") public ExceptionCase assign(@PathVariable UUID id, @RequestBody AssignRequest request){return caseService.assign(id, request.userId());}
  @PostMapping("/exception-cases/{id}/status") public ExceptionCase status(@PathVariable UUID id, @RequestBody StatusRequest request){return caseService.status(id, request.status());}
  @PostMapping("/exception-cases/{id}/resolve") public ExceptionCase resolve(@PathVariable UUID id){return caseService.resolve(id);}
  @PostMapping("/exception-cases/{id}/reject") public ExceptionCase rejectCase(@PathVariable UUID id){return caseService.reject(id);}
  @PostMapping("/exception-cases/{id}/cancel") public ExceptionCase cancelCase(@PathVariable UUID id){return caseService.cancel(id);}

  @PostMapping("/suggested-fixes/generate/{validationRunId}") public List<SuggestedFix> generateFixes(@PathVariable UUID validationRunId){return fixService.generate(validationRunId);}
  @GetMapping("/suggested-fixes") public List<SuggestedFix> fixes(@RequestParam UUID validationRunId){return fixService.list(validationRunId);}
  @GetMapping("/suggested-fixes/{id}") public SuggestedFix fix(@PathVariable UUID id){return fixService.get(id);}
  @PostMapping("/suggested-fixes/{id}/accept") public SuggestedFix acceptFix(@PathVariable UUID id){return fixService.accept(id);}
  @PostMapping("/suggested-fixes/{id}/reject") public SuggestedFix rejectFix(@PathVariable UUID id){return fixService.reject(id);}

  @GetMapping("/draft-quotes/review-queue") public List<DraftReviewSummary> quoteReviewQueue(@RequestParam(required = false) String status, @RequestParam(required = false) UUID sourceReviewCaseId, @RequestParam(required = false) String customerRef, @RequestParam(defaultValue = "25") int limit){return draftReviewService.quoteReviewQueue(status, sourceReviewCaseId, customerRef, limit);}
  @PostMapping("/draft-quotes/from-validation/{validationRunId}") public WorkspaceDraftQuoteDto createQuote(@PathVariable UUID validationRunId){return toDto(quoteService.createFromValidation(validationRunId));}
  @GetMapping("/draft-quotes") public List<WorkspaceDraftQuoteDto> quotes(){return quoteService.list().stream().map(WorkspaceController::toDto).toList();}
  @GetMapping("/draft-quotes/{id}") public WorkspaceDraftQuoteDto quote(@PathVariable UUID id){return toDto(quoteService.get(id));}
  @GetMapping("/draft-quotes/{id}/lines") public List<WorkspaceDraftQuoteLineDto> quoteLines(@PathVariable UUID id){return quoteService.lines(id).stream().map(WorkspaceController::toDto).toList();}
  @GetMapping("/draft-quotes/{id}/review") public DraftQuoteDetail quoteReview(@PathVariable UUID id){return draftReviewService.quoteDetail(id);}
  @PatchMapping("/draft-quotes/{id}/lines/{lineId}") public DraftQuoteDetail correctQuoteLine(@PathVariable UUID id, @PathVariable UUID lineId, @RequestBody DraftLineCorrectionRequest request, HttpServletRequest http){return draftReviewService.correctQuoteLine(id, lineId, trustedCorrection(request, http));}
  @PostMapping("/draft-quotes/{id}/mark-ready") public DraftQuoteDetail markQuoteReady(@PathVariable UUID id, @RequestBody(required = false) ReviewActionRequest request, HttpServletRequest http){return draftReviewService.markQuoteReady(id, trustedReviewAction(request, http));}
  @PostMapping("/draft-quotes/{id}/approve-internal") public WorkspaceDraftQuoteDto approveQuote(@PathVariable UUID id){return toDto(quoteService.approve(id));}
  @PostMapping("/draft-quotes/{id}/reject") public WorkspaceDraftQuoteDto rejectQuote(@PathVariable UUID id){return toDto(quoteService.reject(id));}
  @PostMapping("/draft-quotes/{id}/cancel") public WorkspaceDraftQuoteDto cancelQuote(@PathVariable UUID id){return toDto(quoteService.cancel(id));}

  @GetMapping("/draft-orders/review-queue") public List<DraftReviewSummary> orderReviewQueue(@RequestParam(required = false) String status, @RequestParam(required = false) UUID sourceReviewCaseId, @RequestParam(required = false) String customerRef, @RequestParam(defaultValue = "25") int limit){return draftReviewService.orderReviewQueue(status, sourceReviewCaseId, customerRef, limit);}
  @PostMapping("/draft-orders/from-validation/{validationRunId}") public WorkspaceDraftOrderDto createOrder(@PathVariable UUID validationRunId){return toDto(orderService.createFromValidation(validationRunId));}
  @GetMapping("/draft-orders") public List<WorkspaceDraftOrderDto> orders(){return orderService.list().stream().map(WorkspaceController::toDto).toList();}
  @GetMapping("/draft-orders/{id}") public WorkspaceDraftOrderDto order(@PathVariable UUID id){return toDto(orderService.get(id));}
  @GetMapping("/draft-orders/{id}/lines") public List<WorkspaceDraftOrderLineDto> orderLines(@PathVariable UUID id){return orderService.lines(id).stream().map(WorkspaceController::toDto).toList();}
  @GetMapping("/draft-orders/{id}/review") public DraftOrderDetail orderReview(@PathVariable UUID id){return draftReviewService.orderDetail(id);}
  @PatchMapping("/draft-orders/{id}/lines/{lineId}") public DraftOrderDetail correctOrderLine(@PathVariable UUID id, @PathVariable UUID lineId, @RequestBody DraftLineCorrectionRequest request, HttpServletRequest http){return draftReviewService.correctOrderLine(id, lineId, trustedCorrection(request, http));}
  @PostMapping("/draft-orders/{id}/mark-ready") public DraftOrderDetail markOrderReady(@PathVariable UUID id, @RequestBody(required = false) ReviewActionRequest request, HttpServletRequest http){return draftReviewService.markOrderReady(id, trustedReviewAction(request, http));}
  @PostMapping("/draft-orders/{id}/approve-internal") public WorkspaceDraftOrderDto approveOrder(@PathVariable UUID id){return toDto(orderService.approve(id));}
  @PostMapping("/draft-orders/{id}/reject") public WorkspaceDraftOrderDto rejectOrder(@PathVariable UUID id){return toDto(orderService.reject(id));}
  @PostMapping("/draft-orders/{id}/cancel") public WorkspaceDraftOrderDto cancelOrder(@PathVariable UUID id){return toDto(orderService.cancel(id));}

  @PostMapping("/approval-decisions") public ApprovalDecision decide(@RequestBody ApprovalDecisionRequest request, HttpServletRequest http){return approvalService.decide(request.targetType(), request.targetId(), request.decision(), request.reason(), trustedActor(http));}
  @GetMapping("/approval-decisions") public List<ApprovalDecision> decisions(@RequestParam String targetType, @RequestParam UUID targetId){return approvalService.forTarget(targetType, targetId);}
  @GetMapping("/timeline") public List<WorkspaceTimelineService.TimelineItem> timeline(@RequestParam String targetType, @RequestParam UUID targetId){return timelineService.timeline(targetType, targetId);}
  @PostMapping("/notes") public WorkspaceNoteDto addNote(@RequestBody NoteRequest request, HttpServletRequest http){return toDto(noteService.add(request.targetType(), request.targetId(), request.noteText(), trustedActor(http)));}
  @GetMapping("/notes") public List<WorkspaceNoteDto> notes(@RequestParam String targetType, @RequestParam UUID targetId){return noteService.list(targetType, targetId).stream().map(WorkspaceController::toDto).toList();}
  @GetMapping("/summary") public WorkspaceSummaryService.WorkspaceSummary summary(){return summaryService.summary();}

  // OP-CAP-09D: read-only, tenant-scoped product picker for line correction. No cost/margin/secret fields.
  @GetMapping("/products/search") public List<ProductPickerItem> searchProducts(@RequestParam(required = false) String q, @RequestParam(defaultValue = "10") int limit){return draftReviewService.searchProducts(q, limit);}

  private DraftLineCorrectionRequest trustedCorrection(DraftLineCorrectionRequest request, HttpServletRequest http) {
    if (request == null) {
      return null;
    }
    return new DraftLineCorrectionRequest(request.quantity(), request.uom(), request.description(), request.unitPrice(), request.productId(), request.correctionReason(), trustedActor(http));
  }

  private ReviewActionRequest trustedReviewAction(ReviewActionRequest request, HttpServletRequest http) {
    return new ReviewActionRequest(trustedActor(http), request == null ? null : request.reason());
  }

  private UUID trustedActor(HttpServletRequest http) {
    UUID actorId = actorResolver.resolveVerifiedActor(http, TenantContext.requireTenantId());
    return RequestActorResolver.SYSTEM_ACTOR.equals(actorId) ? null : actorId;
  }
}
