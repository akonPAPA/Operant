package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage6Dtos.ReviewActionRequest;
import com.orderpilot.api.dto.Stage6Dtos.ReviewCaseDetail;
import com.orderpilot.api.dto.Stage6Dtos.ReviewCaseSummary;
import com.orderpilot.api.dto.Stage6Dtos.CorrectQuantityRequest;
import com.orderpilot.api.dto.Stage6Dtos.CorrectUomRequest;
import com.orderpilot.api.dto.Stage6Dtos.IssueDispositionRequest;
import com.orderpilot.api.dto.Stage6Dtos.MapProductRequest;
import com.orderpilot.api.dto.Stage6Dtos.DraftPreview;
import com.orderpilot.api.dto.Stage6Dtos.SubstituteDecisionRequest;
import com.orderpilot.application.services.workspace.DraftCommandPreparationService;
import com.orderpilot.application.services.workspace.ValidationReviewService;
import com.orderpilot.domain.workspace.DraftOrder;
import com.orderpilot.domain.workspace.DraftQuote;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class ValidationReviewController {
  private final ValidationReviewService reviewService;
  private final DraftCommandPreparationService draftCommandPreparationService;

  public ValidationReviewController(ValidationReviewService reviewService, DraftCommandPreparationService draftCommandPreparationService) {
    this.reviewService = reviewService;
    this.draftCommandPreparationService = draftCommandPreparationService;
  }

  @PostMapping("/api/v1/extractions/{extractionId}/validation/review-case")
  public ReviewCaseDetail createReviewCase(@PathVariable UUID extractionId) {
    return reviewService.createForExtractionResult(extractionId);
  }

  @GetMapping("/api/v1/validation-review")
  public List<ReviewCaseSummary> listReviewCases() {
    return reviewService.list();
  }

  @GetMapping("/api/v1/validation-review/{reviewCaseId}")
  public ReviewCaseDetail getReviewCase(@PathVariable UUID reviewCaseId) {
    return reviewService.get(reviewCaseId);
  }

  @PostMapping("/api/v1/validation-review/{reviewCaseId}/approve")
  public ReviewCaseDetail approve(@PathVariable UUID reviewCaseId, @RequestBody(required = false) ReviewActionRequest request) {
    return reviewService.approveForDraft(reviewCaseId, actor(request));
  }

  @PostMapping("/api/v1/validation-review/{reviewCaseId}/reject")
  public ReviewCaseDetail reject(@PathVariable UUID reviewCaseId, @RequestBody(required = false) ReviewActionRequest request) {
    return reviewService.reject(reviewCaseId, actor(request));
  }

  @PostMapping("/api/v1/validation-review/{reviewCaseId}/corrections/uom")
  public ReviewCaseDetail correctUom(@PathVariable UUID reviewCaseId, @RequestBody CorrectUomRequest request) {
    return reviewService.correctUom(reviewCaseId, request.lineItemId(), request.normalizedUom(), request.actorUserId());
  }

  @PostMapping("/api/v1/validation-review/{reviewCaseId}/corrections/quantity")
  public ReviewCaseDetail correctQuantity(@PathVariable UUID reviewCaseId, @RequestBody CorrectQuantityRequest request) {
    return reviewService.correctQuantity(reviewCaseId, request.lineItemId(), request.normalizedQuantity(), request.actorUserId());
  }

  @PostMapping("/api/v1/validation-review/{reviewCaseId}/corrections/product")
  public ReviewCaseDetail mapProduct(@PathVariable UUID reviewCaseId, @RequestBody MapProductRequest request) {
    return reviewService.mapProduct(reviewCaseId, request.lineItemId(), request.productId(), request.actorUserId());
  }

  @PostMapping("/api/v1/validation-review/{reviewCaseId}/substitutes/select")
  public ReviewCaseDetail selectSubstitute(@PathVariable UUID reviewCaseId, @RequestBody SubstituteDecisionRequest request) {
    return reviewService.selectSubstitute(reviewCaseId, request.candidateId(), request.actorUserId(), request.reason());
  }

  @PostMapping("/api/v1/validation-review/{reviewCaseId}/substitutes/reject")
  public ReviewCaseDetail rejectSubstitute(@PathVariable UUID reviewCaseId, @RequestBody SubstituteDecisionRequest request) {
    return reviewService.rejectSubstitute(reviewCaseId, request.candidateId(), request.actorUserId(), request.reason());
  }

  @PostMapping("/api/v1/validation-review/{reviewCaseId}/issues/acknowledge")
  public ReviewCaseDetail acknowledgeIssue(@PathVariable UUID reviewCaseId, @RequestBody IssueDispositionRequest request) {
    return reviewService.acknowledgeIssue(reviewCaseId, request.issueId(), request.actorUserId());
  }

  @PostMapping("/api/v1/validation-review/{reviewCaseId}/issues/override")
  public ReviewCaseDetail overrideIssue(@PathVariable UUID reviewCaseId, @RequestBody IssueDispositionRequest request) {
    return reviewService.overrideIssue(reviewCaseId, request.issueId(), request.actorUserId(), request.reason());
  }

  @PostMapping("/api/v1/validation-review/{reviewCaseId}/approvals/{approvalRequestId}/approve")
  public ReviewCaseDetail approveApproval(@PathVariable UUID reviewCaseId, @PathVariable UUID approvalRequestId, @RequestBody(required = false) ReviewActionRequest request) {
    return reviewService.approveApproval(reviewCaseId, approvalRequestId, actor(request), reason(request));
  }

  @PostMapping("/api/v1/validation-review/{reviewCaseId}/approvals/{approvalRequestId}/reject")
  public ReviewCaseDetail rejectApproval(@PathVariable UUID reviewCaseId, @PathVariable UUID approvalRequestId, @RequestBody(required = false) ReviewActionRequest request) {
    return reviewService.rejectApproval(reviewCaseId, approvalRequestId, actor(request), reason(request));
  }

  @PostMapping("/api/v1/validation-review/{reviewCaseId}/prepare-draft-quote")
  public DraftQuote prepareDraftQuote(@PathVariable UUID reviewCaseId, @RequestBody(required = false) ReviewActionRequest request) {
    return draftCommandPreparationService.prepareDraftQuote(reviewCaseId, actor(request));
  }

  @GetMapping("/api/v1/validation-review/{reviewCaseId}/draft-preview")
  public DraftPreview draftPreview(@PathVariable UUID reviewCaseId, @RequestParam(defaultValue = "QUOTE") String targetType) {
    return draftCommandPreparationService.preview(reviewCaseId, targetType, null);
  }

  @PostMapping("/api/v1/validation-review/{reviewCaseId}/prepare-draft-order")
  public DraftOrder prepareDraftOrder(@PathVariable UUID reviewCaseId, @RequestBody(required = false) ReviewActionRequest request) {
    return draftCommandPreparationService.prepareDraftOrder(reviewCaseId, actor(request));
  }

  private UUID actor(ReviewActionRequest request) {
    return request == null ? null : request.actorUserId();
  }

  private String reason(ReviewActionRequest request) {
    return request == null ? null : request.reason();
  }
}
