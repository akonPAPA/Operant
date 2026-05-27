package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage6Dtos.*;
import com.orderpilot.application.services.workspace.OperatorReviewService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/operator-review")
public class OperatorReviewController {
  private final OperatorReviewService service;

  public OperatorReviewController(OperatorReviewService service) {
    this.service = service;
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
  public ReviewCaseDetail start(@PathVariable UUID id, @RequestBody(required = false) ReviewActionRequest request) {
    return service.startReview(id, actor(request));
  }

  @PostMapping("/cases/{id}/approve-for-next-stage")
  public ReviewCaseDetail approve(@PathVariable UUID id, @RequestBody(required = false) ReviewActionRequest request) {
    return service.approveForNextStep(id, actor(request));
  }

  @PostMapping("/cases/{id}/reject")
  public ReviewCaseDetail reject(@PathVariable UUID id, @RequestBody(required = false) ReviewActionRequest request) {
    return service.reject(id, actor(request));
  }

  @PostMapping("/cases/{id}/request-correction")
  public ReviewCaseDetail correction(@PathVariable UUID id, @RequestBody(required = false) ReviewActionRequest request) {
    return service.requestCorrection(id, actor(request));
  }

  @PostMapping("/cases/{id}/escalate")
  public ReviewCaseDetail escalate(@PathVariable UUID id, @RequestBody(required = false) ReviewActionRequest request) {
    return service.escalate(id, actor(request));
  }

  @PostMapping("/cases/{id}/notes")
  public ReviewCaseDetail addNote(@PathVariable UUID id, @RequestBody ReviewNoteRequest request) {
    return service.addNote(id, request.noteText(), request.createdBy());
  }

  @PostMapping("/cases/{id}/confirm-candidate")
  public ReviewCaseDetail confirmCandidate(@PathVariable UUID id, @RequestBody ConfirmCandidateRequest request) {
    return service.confirmCandidateMatch(id, request.suggestedFixId(), request.actorUserId());
  }

  private UUID actor(ReviewActionRequest request) {
    return request == null ? null : request.actorUserId();
  }
}
