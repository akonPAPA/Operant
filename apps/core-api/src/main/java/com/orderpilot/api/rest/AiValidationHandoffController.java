package com.orderpilot.api.rest;

import com.orderpilot.api.dto.AiValidationHandoffDtos.AiValidationHandoffView;
import com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffCorrectionRequest;
import com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffDecisionRequest;
import com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffDraftPreparationCandidate;
import com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffReviewQueueItem;
import com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffReviewView;
import com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffStartReviewRequest;
import com.orderpilot.application.services.validation.AiValidationHandoffService;
import com.orderpilot.application.services.validation.AiValidationHandoffReviewService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.security.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-07F AI validation handoff endpoints.
 *
 * <p>Generation is internal/service-facing ({@code POST /api/v1/internal/ai-validations/{id}/handoff},
 * guarded by {@code REVIEW_ACTION} via {@code ApiPermissionInterceptor}); the read/list views are
 * operator-facing under {@code /api/v1/ai-validation-handoffs} (guarded by {@code REVIEW_READ}).
 * Tenant is resolved server-side from {@code TenantContext}; it is never trusted from the request.
 * None of these operations creates a quote/order, mutates master data, or triggers an external write —
 * they only produce/return the advisory handoff routing record.
 */
@RestController
public class AiValidationHandoffController {
  private final AiValidationHandoffService service;
  private final AiValidationHandoffReviewService reviewService;
  private final RequestActorResolver actorResolver;

  public AiValidationHandoffController(
      AiValidationHandoffService service,
      AiValidationHandoffReviewService reviewService,
      RequestActorResolver actorResolver) {
    this.service = service;
    this.reviewService = reviewService;
    this.actorResolver = actorResolver;
  }

  @PostMapping("/api/v1/internal/ai-validations/{validationId}/handoff")
  public AiValidationHandoffView generate(@PathVariable UUID validationId) {
    return service.generate(validationId);
  }

  @GetMapping("/api/v1/ai-validation-handoffs/{handoffId}")
  public AiValidationHandoffView get(@PathVariable UUID handoffId) {
    return service.get(handoffId);
  }

  @GetMapping("/api/v1/ai-validation-handoffs")
  public List<AiValidationHandoffView> list(
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "routingDecision", required = false) String routingDecision,
      @RequestParam(name = "limit", required = false) Integer limit) {
    return service.list(status, routingDecision, limit);
  }

  @GetMapping("/api/v1/ai-validation-handoffs/review-queue")
  public List<AiHandoffReviewQueueItem> reviewQueue(
      @RequestParam(name = "reviewStatus", required = false) String reviewStatus,
      @RequestParam(name = "routingDecision", required = false) String routingDecision,
      @RequestParam(name = "riskLevel", required = false) String riskLevel,
      @RequestParam(name = "draftEligible", required = false) Boolean draftEligible,
      @RequestParam(name = "limit", required = false) Integer limit) {
    return reviewService.queue(reviewStatus, routingDecision, riskLevel, draftEligible, limit);
  }

  @GetMapping("/api/v1/ai-validation-handoffs/{handoffId}/review")
  public AiHandoffReviewView getReview(@PathVariable UUID handoffId) {
    return reviewService.get(handoffId);
  }

  @GetMapping("/api/v1/ai-validation-handoffs/{handoffId}/draft-preparation-candidate")
  public AiHandoffDraftPreparationCandidate draftPreparationCandidate(@PathVariable UUID handoffId) {
    return reviewService.draftPreparationCandidate(handoffId);
  }

  @PostMapping("/api/v1/ai-validation-handoffs/{handoffId}/review/start")
  public AiHandoffReviewView startReview(
      @PathVariable UUID handoffId,
      @RequestBody(required = false) AiHandoffStartReviewRequest request,
      HttpServletRequest http) {
    return reviewService.startReview(handoffId, trustedActor(http));
  }

  @PostMapping("/api/v1/ai-validation-handoffs/{handoffId}/review/decision")
  public AiHandoffReviewView decide(
      @PathVariable UUID handoffId,
      @RequestBody(required = false) AiHandoffDecisionRequest request,
      HttpServletRequest http) {
    return reviewService.decide(handoffId, request, trustedActor(http));
  }

  @PostMapping("/api/v1/ai-validation-handoffs/{handoffId}/review/correction")
  public AiHandoffReviewView recordCorrection(
      @PathVariable UUID handoffId,
      @RequestBody(required = false) AiHandoffCorrectionRequest request,
      HttpServletRequest http) {
    return reviewService.recordCorrection(handoffId, request, trustedActor(http));
  }

  private UUID trustedActor(HttpServletRequest http) {
    return actorResolver.resolveVerifiedActor(http, TenantContext.requireTenantId());
  }
}
