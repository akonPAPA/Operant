package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage6Dtos.*;
import com.orderpilot.application.services.workspace.OperatorReviewService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.security.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/operator-review")
public class OperatorReviewController {
  private final OperatorReviewService service;
  private final RequestActorResolver actorResolver;

  public OperatorReviewController(OperatorReviewService service, RequestActorResolver actorResolver) {
    this.service = service;
    this.actorResolver = actorResolver;
  }

  @PostMapping("/validation-runs/{validationRunId}/cases")
  public ReviewCaseDetail createCase(@PathVariable UUID validationRunId) {
    return service.createForValidationRun(validationRunId);
  }

  @GetMapping("/cases")
  public List<ReviewCaseSummary> cases() {
    return service.list();
  }

  @GetMapping("/cases/{id}")
  public ReviewCaseDetail caseDetail(@PathVariable UUID id) {
    return service.detail(id);
  }

  @PostMapping("/cases/{id}/start")
  public ReviewCaseDetail start(@PathVariable UUID id, @RequestBody(required = false) ReviewActionRequest request, HttpServletRequest http) {
    return service.startReview(id, trustedActor(http));
  }

  @PostMapping("/cases/{id}/approve-for-next-stage")
  public ReviewCaseDetail approve(@PathVariable UUID id, @RequestBody(required = false) ReviewActionRequest request, HttpServletRequest http) {
    return service.approveForNextStep(id, trustedActor(http));
  }

  @PostMapping("/cases/{id}/reject")
  public ReviewCaseDetail reject(@PathVariable UUID id, @RequestBody(required = false) ReviewActionRequest request, HttpServletRequest http) {
    return service.reject(id, trustedActor(http));
  }

  @PostMapping("/cases/{id}/request-correction")
  public ReviewCaseDetail correction(@PathVariable UUID id, @RequestBody(required = false) ReviewActionRequest request, HttpServletRequest http) {
    return service.requestCorrection(id, trustedActor(http));
  }

  @PostMapping("/cases/{id}/escalate")
  public ReviewCaseDetail escalate(@PathVariable UUID id, @RequestBody(required = false) ReviewActionRequest request, HttpServletRequest http) {
    return service.escalate(id, trustedActor(http));
  }

  @PostMapping("/cases/{id}/notes")
  public ReviewCaseDetail addNote(@PathVariable UUID id, @RequestBody ReviewNoteRequest request, HttpServletRequest http) {
    return service.addNote(id, request.noteText(), trustedActor(http));
  }

  @PostMapping("/cases/{id}/confirm-candidate")
  public ReviewCaseDetail confirmCandidate(@PathVariable UUID id, @RequestBody ConfirmCandidateRequest request, HttpServletRequest http) {
    return service.confirmCandidateMatch(id, request.suggestedFixId(), trustedActor(http));
  }

  private UUID trustedActor(HttpServletRequest http) {
    return actorResolver.resolveVerifiedActor(http, TenantContext.getTenantId().orElse(null));
  }
}
