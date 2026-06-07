package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage10BDtos.*;
import com.orderpilot.application.services.pilot.PilotShadowModeService;
import com.orderpilot.application.services.pilot.PilotShadowModeService.ExceptionCategorySlice;
import com.orderpilot.application.services.pilot.PilotShadowModeService.PilotMetrics;
import com.orderpilot.domain.pilot.HumanCorrection;
import com.orderpilot.domain.pilot.ShadowRun;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/pilot")
public class PilotController {
  private final PilotShadowModeService service;

  public PilotController(PilotShadowModeService service) {
    this.service = service;
  }

  @PostMapping("/shadow-runs")
  public ShadowRunResponse createShadowRun(@RequestBody ShadowRunRequest request) {
    return toShadowRun(service.recordShadowRun(
        request.sourceType(), request.sourceId(), request.predictionType(), request.providerLabel(),
        request.predictionPayloadJson(), request.confidenceScore(),
        request.exceptionCategory(), request.manualBaselineMinutes(), request.assistedProcessingMinutes(),
        Boolean.TRUE.equals(request.automationCandidate()), Boolean.TRUE.equals(request.reviewRequired())));
  }

  @GetMapping("/shadow-runs")
  public List<ShadowRunResponse> shadowRuns(@RequestParam(required = false) String sourceType, @RequestParam(required = false) String status) {
    return service.listShadowRuns(sourceType, status).stream().map(this::toShadowRun).toList();
  }

  @PostMapping("/shadow-runs/{id}/corrections")
  public HumanCorrectionResponse correct(@PathVariable UUID id, @RequestBody HumanCorrectionRequest request) {
    return toCorrection(service.recordCorrection(id, request.correctedByUserId(), request.correctionType(), request.beforePayloadJson(), request.afterPayloadJson(), request.correctionReason()));
  }

  @GetMapping("/metrics")
  public PilotMetricResponse metrics() {
    PilotMetrics metrics = service.metrics();
    return new PilotMetricResponse(
        metrics.totalShadowRuns(), metrics.reviewedShadowRuns(), metrics.acceptedCount(), metrics.correctedCount(), metrics.rejectedCount(),
        metrics.humanCorrectionRate(), metrics.averageConfidence(), metrics.exceptionCategoryCounts(), metrics.predictionTypeBreakdown(), metrics.correctionTypeBreakdown(),
        metrics.automationCandidateCount(), metrics.reviewRequiredCount(), metrics.averageManualBaselineMinutes(), metrics.averageAssistedMinutes(),
        metrics.estimatedMinutesSaved(), metrics.estimatedCostSaved(), metrics.costCurrency());
  }

  @GetMapping("/metrics/exceptions")
  public PilotExceptionBreakdownResponse exceptions() {
    List<ExceptionCategorySlice> slices = service.exceptionBreakdown();
    long totalCategorized = slices.stream().mapToLong(ExceptionCategorySlice::count).sum();
    List<ExceptionCategoryResponse> categories = slices.stream()
        .map(slice -> new ExceptionCategoryResponse(slice.category(), slice.count(), slice.percentage()))
        .toList();
    return new PilotExceptionBreakdownResponse(totalCategorized, categories);
  }

  private ShadowRunResponse toShadowRun(ShadowRun run) {
    return new ShadowRunResponse(
        run.getId(), run.getSourceType(), run.getSourceId(), run.getPredictionType(), run.getProviderMode(), run.getProviderLabel(),
        hasContent(run.getPredictionPayloadJson()), run.getConfidenceScore(), run.getStatus(),
        run.getExceptionCategory(), run.getManualBaselineMinutes(), run.getAssistedProcessingMinutes(),
        run.isAutomationCandidate(), run.isReviewRequired(), run.getCreatedAt(), run.getReviewedAt());
  }

  private HumanCorrectionResponse toCorrection(HumanCorrection correction) {
    return new HumanCorrectionResponse(
        correction.getId(), correction.getShadowRunId(), correction.getCorrectedByUserId(), correction.getCorrectionType(),
        hasContent(correction.getBeforePayloadJson()), hasContent(correction.getAfterPayloadJson()), correction.getCorrectionReason(), correction.getCreatedAt());
  }

  private static boolean hasContent(String payloadJson) {
    return payloadJson != null && !payloadJson.isBlank() && !"{}".equals(payloadJson.trim());
  }
}
