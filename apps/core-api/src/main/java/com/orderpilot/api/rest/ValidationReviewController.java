package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage6Dtos.ReviewActionRequest;
import com.orderpilot.api.dto.Stage6Dtos.ReviewCaseDetail;
import com.orderpilot.api.dto.Stage6Dtos.ReviewCaseSummary;
import com.orderpilot.api.dto.Stage6Dtos.CorrectQuantityRequest;
import com.orderpilot.api.dto.Stage6Dtos.CorrectUomRequest;
import com.orderpilot.api.dto.Stage6Dtos.IssueDispositionRequest;
import com.orderpilot.api.dto.Stage6Dtos.MapProductRequest;
import com.orderpilot.api.dto.Stage6Dtos.DraftPreview;
import com.orderpilot.api.dto.Stage6Dtos.DraftPreparationResult;
import com.orderpilot.api.dto.Stage6Dtos.SubstituteDecisionRequest;
import com.orderpilot.api.dto.Stage6Dtos.WorkspaceDraftOrderDto;
import com.orderpilot.api.dto.Stage6Dtos.WorkspaceDraftQuoteDto;
import com.orderpilot.application.services.workspace.DraftCommandPreparationService;
import com.orderpilot.application.services.workspace.ValidationReviewService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.workspace.DraftOrder;
import com.orderpilot.domain.workspace.DraftQuote;
import com.orderpilot.security.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class ValidationReviewController {
  private final ValidationReviewService reviewService;
  private final DraftCommandPreparationService draftCommandPreparationService;
  private final RequestActorResolver actorResolver;

  public ValidationReviewController(ValidationReviewService reviewService, DraftCommandPreparationService draftCommandPreparationService, RequestActorResolver actorResolver) {
    this.reviewService = reviewService;
    this.draftCommandPreparationService = draftCommandPreparationService;
    this.actorResolver = actorResolver;
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
  public ReviewCaseDetail approve(@PathVariable UUID reviewCaseId, @RequestBody(required = false) ReviewActionRequest request, HttpServletRequest http) {
    return reviewService.approveForDraft(reviewCaseId, trustedActor(http));
  }

  @PostMapping("/api/v1/validation-review/{reviewCaseId}/reject")
  public ReviewCaseDetail reject(@PathVariable UUID reviewCaseId, @RequestBody(required = false) ReviewActionRequest request, HttpServletRequest http) {
    return reviewService.reject(reviewCaseId, trustedActor(http));
  }

  @PostMapping("/api/v1/validation-review/{reviewCaseId}/corrections/uom")
  public ReviewCaseDetail correctUom(@PathVariable UUID reviewCaseId, @RequestBody CorrectUomRequest request, HttpServletRequest http) {
    return reviewService.correctUom(reviewCaseId, request.lineItemId(), request.normalizedUom(), trustedActor(http));
  }

  @PostMapping("/api/v1/validation-review/{reviewCaseId}/corrections/quantity")
  public ReviewCaseDetail correctQuantity(@PathVariable UUID reviewCaseId, @RequestBody CorrectQuantityRequest request, HttpServletRequest http) {
    return reviewService.correctQuantity(reviewCaseId, request.lineItemId(), request.normalizedQuantity(), trustedActor(http));
  }

  @PostMapping("/api/v1/validation-review/{reviewCaseId}/corrections/product")
  public ReviewCaseDetail mapProduct(@PathVariable UUID reviewCaseId, @RequestBody MapProductRequest request, HttpServletRequest http) {
    return reviewService.mapProduct(reviewCaseId, request.lineItemId(), request.productId(), trustedActor(http));
  }

  @PostMapping("/api/v1/validation-review/{reviewCaseId}/substitutes/select")
  public ReviewCaseDetail selectSubstitute(@PathVariable UUID reviewCaseId, @RequestBody SubstituteDecisionRequest request, HttpServletRequest http) {
    return reviewService.selectSubstitute(reviewCaseId, request.candidateId(), trustedActor(http), request.reason());
  }

  @PostMapping("/api/v1/validation-review/{reviewCaseId}/substitutes/reject")
  public ReviewCaseDetail rejectSubstitute(@PathVariable UUID reviewCaseId, @RequestBody SubstituteDecisionRequest request, HttpServletRequest http) {
    return reviewService.rejectSubstitute(reviewCaseId, request.candidateId(), trustedActor(http), request.reason());
  }

  @PostMapping("/api/v1/validation-review/{reviewCaseId}/issues/acknowledge")
  public ReviewCaseDetail acknowledgeIssue(@PathVariable UUID reviewCaseId, @RequestBody IssueDispositionRequest request, HttpServletRequest http) {
    return reviewService.acknowledgeIssue(reviewCaseId, request.issueId(), trustedActor(http));
  }

  @PostMapping("/api/v1/validation-review/{reviewCaseId}/issues/override")
  public ReviewCaseDetail overrideIssue(@PathVariable UUID reviewCaseId, @RequestBody IssueDispositionRequest request, HttpServletRequest http) {
    return reviewService.overrideIssue(reviewCaseId, request.issueId(), trustedActor(http), request.reason());
  }

  @PostMapping("/api/v1/validation-review/{reviewCaseId}/approvals/{approvalRequestId}/approve")
  public ReviewCaseDetail approveApproval(@PathVariable UUID reviewCaseId, @PathVariable UUID approvalRequestId, @RequestBody(required = false) ReviewActionRequest request, HttpServletRequest http) {
    return reviewService.approveApproval(reviewCaseId, approvalRequestId, trustedActor(http), reason(request));
  }

  @PostMapping("/api/v1/validation-review/{reviewCaseId}/approvals/{approvalRequestId}/reject")
  public ReviewCaseDetail rejectApproval(@PathVariable UUID reviewCaseId, @PathVariable UUID approvalRequestId, @RequestBody(required = false) ReviewActionRequest request, HttpServletRequest http) {
    return reviewService.rejectApproval(reviewCaseId, approvalRequestId, trustedActor(http), reason(request));
  }

  @PostMapping("/api/v1/validation-review/{reviewCaseId}/prepare-draft-quote")
  public WorkspaceDraftQuoteDto prepareDraftQuote(@PathVariable UUID reviewCaseId, @RequestBody(required = false) ReviewActionRequest request, HttpServletRequest http) {
    return toDto(draftCommandPreparationService.prepareDraftQuote(reviewCaseId, trustedActor(http)));
  }

  @GetMapping("/api/v1/validation-review/{reviewCaseId}/draft-preview")
  public DraftPreview draftPreview(@PathVariable UUID reviewCaseId, @RequestParam(defaultValue = "QUOTE") String targetType) {
    return draftCommandPreparationService.preview(reviewCaseId, targetType, null);
  }

  // OP-CAP-09A: intent-driven, idempotent internal draft preparation (REVIEW_ACTION). No external/ERP write; no final quote/order.
  @PostMapping("/api/v1/validation-review/{reviewCaseId}/prepare-draft")
  public DraftPreparationResult prepareDraft(@PathVariable UUID reviewCaseId, @RequestBody(required = false) ReviewActionRequest request, HttpServletRequest http) {
    return draftCommandPreparationService.prepareDraft(reviewCaseId, trustedActor(http));
  }

  @PostMapping("/api/v1/validation-review/{reviewCaseId}/prepare-draft-order")
  public WorkspaceDraftOrderDto prepareDraftOrder(@PathVariable UUID reviewCaseId, @RequestBody(required = false) ReviewActionRequest request, HttpServletRequest http) {
    return toDto(draftCommandPreparationService.prepareDraftOrder(reviewCaseId, trustedActor(http)));
  }

  private UUID trustedActor(HttpServletRequest http) {
    return actorResolver.resolveVerifiedActor(http, TenantContext.getTenantId().orElse(null));
  }

  private static WorkspaceDraftQuoteDto toDto(DraftQuote q) {
    if (q == null) return null;
    return new WorkspaceDraftQuoteDto(q.getId(), q.getQuoteNumber(), q.getCustomerAccountId(), q.getCustomerDisplayName(), q.getStatus(), q.getValidationStatus(), q.isRequiresHumanReview(), q.getCurrency(), q.getSubtotalAmount(), q.getDiscountAmount(), q.getTotalAmount(), q.getMarginPercent(), q.getCreatedAt());
  }

  private static WorkspaceDraftOrderDto toDto(DraftOrder o) {
    if (o == null) return null;
    return new WorkspaceDraftOrderDto(o.getId(), o.getOrderNumber(), o.getCustomerAccountId(), o.getStatus(), o.getCurrency(), o.getSubtotalAmount(), o.getDiscountAmount(), o.getTotalAmount(), o.getMarginPercent(), o.getCreatedAt());
  }

  private String reason(ReviewActionRequest request) {
    return request == null ? null : request.reason();
  }
}
