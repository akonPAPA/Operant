package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage6Dtos.*;
import com.orderpilot.application.services.workspace.*;
import com.orderpilot.domain.workspace.*;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspace")
public class WorkspaceController {
  private final ExceptionCaseService caseService; private final SuggestedFixService fixService; private final DraftQuoteService quoteService; private final DraftOrderService orderService; private final DraftReviewService draftReviewService; private final ApprovalWorkflowService approvalService; private final WorkspaceTimelineService timelineService; private final WorkspaceNoteService noteService; private final WorkspaceSummaryService summaryService;
  public WorkspaceController(ExceptionCaseService caseService, SuggestedFixService fixService, DraftQuoteService quoteService, DraftOrderService orderService, DraftReviewService draftReviewService, ApprovalWorkflowService approvalService, WorkspaceTimelineService timelineService, WorkspaceNoteService noteService, WorkspaceSummaryService summaryService){this.caseService=caseService;this.fixService=fixService;this.quoteService=quoteService;this.orderService=orderService;this.draftReviewService=draftReviewService;this.approvalService=approvalService;this.timelineService=timelineService;this.noteService=noteService;this.summaryService=summaryService;}

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

  @PostMapping("/draft-quotes/from-validation/{validationRunId}") public DraftQuote createQuote(@PathVariable UUID validationRunId){return quoteService.createFromValidation(validationRunId);}
  @GetMapping("/draft-quotes") public List<DraftQuote> quotes(){return quoteService.list();}
  @GetMapping("/draft-quotes/{id}") public DraftQuote quote(@PathVariable UUID id){return quoteService.get(id);}
  @GetMapping("/draft-quotes/{id}/lines") public List<DraftQuoteLine> quoteLines(@PathVariable UUID id){return quoteService.lines(id);}
  @GetMapping("/draft-quotes/{id}/review") public DraftQuoteDetail quoteReview(@PathVariable UUID id){return draftReviewService.quoteDetail(id);}
  @PatchMapping("/draft-quotes/{id}/lines/{lineId}") public DraftQuoteDetail correctQuoteLine(@PathVariable UUID id, @PathVariable UUID lineId, @RequestBody DraftLineCorrectionRequest request){return draftReviewService.correctQuoteLine(id, lineId, request);}
  @PostMapping("/draft-quotes/{id}/mark-ready") public DraftQuoteDetail markQuoteReady(@PathVariable UUID id, @RequestBody(required = false) ReviewActionRequest request){return draftReviewService.markQuoteReady(id, request);}
  @PostMapping("/draft-quotes/{id}/approve-internal") public DraftQuote approveQuote(@PathVariable UUID id){return quoteService.approve(id);}
  @PostMapping("/draft-quotes/{id}/reject") public DraftQuote rejectQuote(@PathVariable UUID id){return quoteService.reject(id);}
  @PostMapping("/draft-quotes/{id}/cancel") public DraftQuote cancelQuote(@PathVariable UUID id){return quoteService.cancel(id);}

  @PostMapping("/draft-orders/from-validation/{validationRunId}") public DraftOrder createOrder(@PathVariable UUID validationRunId){return orderService.createFromValidation(validationRunId);}
  @GetMapping("/draft-orders") public List<DraftOrder> orders(){return orderService.list();}
  @GetMapping("/draft-orders/{id}") public DraftOrder order(@PathVariable UUID id){return orderService.get(id);}
  @GetMapping("/draft-orders/{id}/lines") public List<DraftOrderLine> orderLines(@PathVariable UUID id){return orderService.lines(id);}
  @GetMapping("/draft-orders/{id}/review") public DraftOrderDetail orderReview(@PathVariable UUID id){return draftReviewService.orderDetail(id);}
  @PatchMapping("/draft-orders/{id}/lines/{lineId}") public DraftOrderDetail correctOrderLine(@PathVariable UUID id, @PathVariable UUID lineId, @RequestBody DraftLineCorrectionRequest request){return draftReviewService.correctOrderLine(id, lineId, request);}
  @PostMapping("/draft-orders/{id}/mark-ready") public DraftOrderDetail markOrderReady(@PathVariable UUID id, @RequestBody(required = false) ReviewActionRequest request){return draftReviewService.markOrderReady(id, request);}
  @PostMapping("/draft-orders/{id}/approve-internal") public DraftOrder approveOrder(@PathVariable UUID id){return orderService.approve(id);}
  @PostMapping("/draft-orders/{id}/reject") public DraftOrder rejectOrder(@PathVariable UUID id){return orderService.reject(id);}
  @PostMapping("/draft-orders/{id}/cancel") public DraftOrder cancelOrder(@PathVariable UUID id){return orderService.cancel(id);}

  @PostMapping("/approval-decisions") public ApprovalDecision decide(@RequestBody ApprovalDecisionRequest request){return approvalService.decide(request.targetType(), request.targetId(), request.decision(), request.reason(), request.decidedBy());}
  @GetMapping("/approval-decisions") public List<ApprovalDecision> decisions(@RequestParam String targetType, @RequestParam UUID targetId){return approvalService.forTarget(targetType, targetId);}
  @GetMapping("/timeline") public List<WorkspaceTimelineService.TimelineItem> timeline(@RequestParam String targetType, @RequestParam UUID targetId){return timelineService.timeline(targetType, targetId);}
  @PostMapping("/notes") public WorkspaceNote addNote(@RequestBody NoteRequest request){return noteService.add(request.targetType(), request.targetId(), request.noteText(), request.createdBy());}
  @GetMapping("/notes") public List<WorkspaceNote> notes(@RequestParam String targetType, @RequestParam UUID targetId){return noteService.list(targetType, targetId);}
  @GetMapping("/summary") public WorkspaceSummaryService.WorkspaceSummary summary(){return summaryService.summary();}
}
