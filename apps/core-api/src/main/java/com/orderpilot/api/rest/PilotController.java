package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage10BDtos.*;
import com.orderpilot.application.services.pilot.PilotDemoScenarioService;
import com.orderpilot.application.services.pilot.PilotDemoScenarioService.DemoScenario;
import com.orderpilot.application.services.pilot.PilotDemoScenarioService.DemoScenarioPack;
import com.orderpilot.application.services.pilot.PilotShadowModeService;
import com.orderpilot.application.services.pilot.PilotShadowModeService.EvidenceReport;
import com.orderpilot.application.services.pilot.PilotShadowModeService.ExceptionCategorySlice;
import com.orderpilot.application.services.pilot.PilotShadowModeService.PilotMetrics;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.pilot.HumanCorrection;
import com.orderpilot.domain.pilot.ShadowRun;
import com.orderpilot.security.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/pilot")
public class PilotController {
  private final PilotShadowModeService service;
  private final PilotDemoScenarioService demoScenarioService;
  private final RequestActorResolver actorResolver;

  public PilotController(
      PilotShadowModeService service,
      PilotDemoScenarioService demoScenarioService,
      RequestActorResolver actorResolver) {
    this.service = service;
    this.demoScenarioService = demoScenarioService;
    this.actorResolver = actorResolver;
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
  public HumanCorrectionResponse correct(
      @PathVariable UUID id,
      @RequestBody HumanCorrectionRequest request,
      HttpServletRequest http) {
    UUID trustedActorId =
        actorResolver.resolveVerifiedActor(http, TenantContext.requireTenantId());
    return toCorrection(service.recordCorrection(
        id,
        trustedActorId,
        request.correctionType(),
        request.beforePayloadJson(),
        request.afterPayloadJson(),
        request.correctionReason()));
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
    return new PilotExceptionBreakdownResponse(totalCategorized, toCategoryResponses(slices));
  }

  @GetMapping("/evidence-report")
  public PilotEvidenceReport evidenceReport() {
    EvidenceReport report = service.evidenceReport();
    PilotMetrics m = report.metrics();
    List<PilotReadinessSignal> signals = report.readinessSignals().stream()
        .map(signal -> new PilotReadinessSignal(signal.label(), signal.value(), signal.assessment()))
        .toList();
    return new PilotEvidenceReport(
        report.reportGeneratedAt(), report.tenantId(), m.totalShadowRuns(), report.totalHumanCorrections(),
        m.averageManualBaselineMinutes(), m.averageAssistedMinutes(), m.estimatedMinutesSaved(), m.estimatedCostSaved(), m.costCurrency(),
        m.automationCandidateCount(), m.reviewRequiredCount(), m.humanCorrectionRate(),
        toCategoryResponses(report.exceptionBreakdown()), toCategoryResponses(report.topExceptionCategories()),
        signals, report.limitations(), report.safetyStatement());
  }

  private static List<ExceptionCategoryResponse> toCategoryResponses(List<ExceptionCategorySlice> slices) {
    return slices.stream()
        .map(slice -> new ExceptionCategoryResponse(slice.category(), slice.count(), slice.percentage()))
        .toList();
  }

  @GetMapping("/demo-scenarios")
  public PilotDemoScenarioPackResponse demoScenarios() {
    DemoScenarioPack pack = demoScenarioService.demoScenarios();
    List<PilotDemoScenarioResponse> scenarios = pack.scenarios().stream().map(PilotController::toScenario).toList();
    return new PilotDemoScenarioPackResponse(
        pack.reportGeneratedAt(), pack.tenantId(), pack.tenantHasPilotEvidence(), scenarios, pack.packLimitations(), pack.safetyStatement());
  }

  private static PilotDemoScenarioResponse toScenario(DemoScenario scenario) {
    List<PilotDemoScenarioCapabilityResponse> capabilities = scenario.requiredCapabilities().stream()
        .map(c -> new PilotDemoScenarioCapabilityResponse(c.name(), c.available(), c.note()))
        .toList();
    List<PilotDemoScenarioEvidenceResponse> evidence = scenario.evidenceSignals().stream()
        .map(e -> new PilotDemoScenarioEvidenceResponse(e.label(), e.value()))
        .toList();
    List<PilotDemoScenarioSafetyBoundaryResponse> boundaries = scenario.safetyBoundaries().stream()
        .map(b -> new PilotDemoScenarioSafetyBoundaryResponse(b.statement()))
        .toList();
    return new PilotDemoScenarioResponse(
        scenario.code(), scenario.title(), scenario.businessObjective(), scenario.primaryActorRole(), scenario.channelSourceType(),
        scenario.readiness().name(), scenario.readinessScore(), capabilities, evidence, scenario.missingCapabilities(),
        boundaries, scenario.suggestedDemoRoute(), scenario.relatedReportLinks(), scenario.operatorTalkingPoints());
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
