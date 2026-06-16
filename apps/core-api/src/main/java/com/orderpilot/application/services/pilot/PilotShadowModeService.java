package com.orderpilot.application.services.pilot;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.analytics.RoiAssumptionsService;
import com.orderpilot.application.services.runtime.RuntimeFeatureType;
import com.orderpilot.application.services.runtime.RuntimeGuardRequest;
import com.orderpilot.application.services.runtime.RuntimeGuardService;
import com.orderpilot.application.services.runtime.RuntimeOperationType;
import com.orderpilot.application.services.runtime.RuntimeUnitEstimateRequest;
import com.orderpilot.application.services.runtime.RuntimeUnitEstimator;
import com.orderpilot.api.dto.Stage8Dtos.RoiAssumptionsResponse;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.pilot.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PilotShadowModeService {
  private final ShadowRunRepository shadowRunRepository;
  private final HumanCorrectionRepository humanCorrectionRepository;
  private final AuditEventService auditEventService;
  private final RoiAssumptionsService roiAssumptionsService;
  private final RuntimeGuardService runtimeGuardService;
  private final RuntimeUnitEstimator runtimeUnitEstimator;
  private final Clock clock;

  public PilotShadowModeService(ShadowRunRepository shadowRunRepository, HumanCorrectionRepository humanCorrectionRepository, AuditEventService auditEventService, RoiAssumptionsService roiAssumptionsService, RuntimeGuardService runtimeGuardService, RuntimeUnitEstimator runtimeUnitEstimator, Clock clock) {
    this.shadowRunRepository = shadowRunRepository;
    this.humanCorrectionRepository = humanCorrectionRepository;
    this.auditEventService = auditEventService;
    this.roiAssumptionsService = roiAssumptionsService;
    this.runtimeGuardService = runtimeGuardService;
    this.runtimeUnitEstimator = runtimeUnitEstimator;
    this.clock = clock;
  }

  @Transactional
  public ShadowRun recordShadowRun(String sourceType, UUID sourceId, String predictionType, String providerLabel, String predictionPayloadJson, BigDecimal confidenceScore) {
    return recordShadowRun(sourceType, sourceId, predictionType, providerLabel, predictionPayloadJson, confidenceScore, null, null, null, false, false);
  }

  @Transactional
  public ShadowRun recordShadowRun(String sourceType, UUID sourceId, String predictionType, String providerLabel, String predictionPayloadJson, BigDecimal confidenceScore,
      String exceptionCategory, BigDecimal manualBaselineMinutes, BigDecimal assistedProcessingMinutes, boolean automationCandidate, boolean reviewRequired) {
    UUID tenantId = TenantContext.requireTenantId();
    requireValue(sourceType, "sourceType");
    requireValue(sourceId, "sourceId");
    requireValue(predictionType, "predictionType");
    ShadowRun run = shadowRunRepository.save(new ShadowRun(tenantId, sourceType, sourceId, predictionType, providerLabel, predictionPayloadJson, confidenceScore,
        normalizeCategory(exceptionCategory), nonNegativeOrNull(manualBaselineMinutes), nonNegativeOrNull(assistedProcessingMinutes), automationCandidate, reviewRequired, clock.instant()));
    auditEventService.record("PILOT_SHADOW_RUN_RECORDED", "SHADOW_RUN", run.getId().toString(), null, "{\"providerMode\":\"MOCK_ONLY\"}");
    return run;
  }

  @Transactional(readOnly = true)
  public List<ShadowRun> listShadowRuns(String sourceType, String status) {
    UUID tenantId = TenantContext.requireTenantId();
    boolean hasSource = sourceType != null && !sourceType.isBlank();
    boolean hasStatus = status != null && !status.isBlank();
    if (hasSource && hasStatus) {
      return shadowRunRepository.findByTenantIdAndSourceTypeAndStatusOrderByCreatedAtDesc(tenantId, sourceType, status);
    }
    if (hasSource) {
      return shadowRunRepository.findByTenantIdAndSourceTypeOrderByCreatedAtDesc(tenantId, sourceType);
    }
    if (hasStatus) {
      return shadowRunRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, status);
    }
    return shadowRunRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
  }

  @Transactional
  public HumanCorrection recordCorrection(UUID shadowRunId, UUID correctedByUserId, String correctionType, String beforePayloadJson, String afterPayloadJson, String correctionReason) {
    UUID tenantId = TenantContext.requireTenantId();
    requireValue(shadowRunId, "shadowRunId");
    requireValue(correctionType, "correctionType");
    ShadowRun shadowRun = shadowRunRepository.findByIdAndTenantId(shadowRunId, tenantId)
        .orElseThrow(() -> new NotFoundException("Shadow run not found: " + shadowRunId));
    HumanCorrection correction = humanCorrectionRepository.save(new HumanCorrection(tenantId, shadowRunId, correctedByUserId, correctionType, beforePayloadJson, afterPayloadJson, correctionReason, clock.instant()));
    shadowRun.markReviewed(statusForCorrection(correctionType), clock.instant());
    auditEventService.record("PILOT_HUMAN_CORRECTION_RECORDED", "SHADOW_RUN", shadowRunId.toString(), correctedByUserId, "{\"correctionType\":\"" + correctionType + "\"}");
    return correction;
  }

  @Transactional(readOnly = true)
  public PilotMetrics metrics() {
    UUID tenantId = TenantContext.requireTenantId();
    List<ShadowRun> runs = shadowRunRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    List<HumanCorrection> corrections = humanCorrectionRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    long total = runs.size();
    long accepted = runs.stream().filter(run -> "ACCEPTED".equals(run.getStatus())).count();
    long corrected = runs.stream().filter(run -> "CORRECTED".equals(run.getStatus())).count();
    long rejected = runs.stream().filter(run -> "REJECTED".equals(run.getStatus())).count();
    long reviewed = runs.stream().filter(PilotShadowModeService::isReviewed).count();
    BigDecimal averageConfidence = averageConfidence(runs);
    BigDecimal correctionRate = reviewed == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(corrected).divide(BigDecimal.valueOf(reviewed), 4, RoundingMode.HALF_UP);

    long automationCandidateCount = runs.stream().filter(ShadowRun::isAutomationCandidate).count();
    long reviewRequiredCount = runs.stream().filter(ShadowRun::isReviewRequired).count();
    BigDecimal averageManualBaselineMinutes = averageOf(runs, ShadowRun::getManualBaselineMinutes);
    BigDecimal averageAssistedMinutes = averageOf(runs, ShadowRun::getAssistedProcessingMinutes);
    BigDecimal estimatedMinutesSaved = estimatedMinutesSaved(runs);

    // Estimated cost saved uses the tenant's safe local/demo ROI assumptions (no external calls).
    RoiAssumptionsResponse roi = roiAssumptionsService.currentForTenant(tenantId);
    BigDecimal estimatedCostSaved = estimatedMinutesSaved
        .divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP)
        .multiply(roi.averageFullyLoadedOperatorHourlyCost())
        .setScale(2, RoundingMode.HALF_UP);

    return new PilotMetrics(total, reviewed, accepted, corrected, rejected, correctionRate, averageConfidence,
        exceptionCategoryCounts(runs), countBy(runs, ShadowRun::getPredictionType), countBy(corrections, HumanCorrection::getCorrectionType),
        automationCandidateCount, reviewRequiredCount, averageManualBaselineMinutes, averageAssistedMinutes, estimatedMinutesSaved, estimatedCostSaved, roi.defaultCurrency());
  }

  // OP-CAP-11G: design-partner evidence report. Composes the existing tenant-scoped pilot
  // metrics and exception breakdown into a structured, non-raw report. No new metric logic.
  static final String SAFETY_STATEMENT =
      "AI suggests, rules validate, humans approve, the backend writes, audit records. "
          + "Shadow-mode results are advisory (MOCK_ONLY) and never auto-approve quotes/orders or trigger "
          + "ERP/1C/connector writes. All figures are tenant-scoped.";
  static final List<String> REPORT_LIMITATIONS = List.of(
      "Shadow-mode predictions are advisory (MOCK_ONLY); no real AI provider is invoked.",
      "Estimated minutes and cost saved are modeled from the tenant's ROI assumptions, not measured billing.",
      "This is evidence of shadow-mode and pilot readiness, not a guarantee of production ROI.",
      "Raw prediction payloads and correction before/after payloads are intentionally excluded.",
      "CSV export is not provided in this slice (no existing safe CSV export convention).");

  @Transactional(readOnly = true)
  public EvidenceReport evidenceReport() {
    UUID tenantId = TenantContext.requireTenantId();
    // OP-CAP-16G runtime guard: entitlement -> quota only (operator-initiated, low-frequency report
    // export — rate limiting is reserved for high-frequency automated paths), BEFORE the heavier
    // metrics/breakdown aggregation below. A disabled REPORT_EXPORT entitlement or an exhausted quota
    // throws a stable mapped 403 and no report work runs. Requested units come from a cheap
    // tenant-scoped COUNT of shadow runs (the report's expected row volume); no rows are loaded for
    // the estimate.
    int requestedUnits =
        runtimeUnitEstimator.estimate(
            RuntimeUnitEstimateRequest.forReport(
                tenantId, (int) Math.min(shadowRunRepository.countByTenantId(tenantId), Integer.MAX_VALUE)));
    runtimeGuardService.enforceWithoutRate(
        RuntimeGuardRequest.of(tenantId, RuntimeOperationType.REPORT_GENERATED, requestedUnits),
        RuntimeFeatureType.REPORT_EXPORT);

    PilotMetrics metrics = metrics();
    List<ExceptionCategorySlice> breakdown = exceptionBreakdown();
    long totalCorrections = humanCorrectionRepository.countByTenantId(tenantId);
    List<ExceptionCategorySlice> topCategories = breakdown.stream()
        .sorted(java.util.Comparator.comparingLong(ExceptionCategorySlice::count).reversed()
            .thenComparing(ExceptionCategorySlice::category))
        .limit(3)
        .toList();
    return new EvidenceReport(clock.instant(), tenantId, metrics, totalCorrections, breakdown, topCategories,
        readinessSignals(metrics), REPORT_LIMITATIONS, SAFETY_STATEMENT);
  }

  private static List<ReadinessSignal> readinessSignals(PilotMetrics metrics) {
    List<ReadinessSignal> signals = new ArrayList<>();
    long total = metrics.totalShadowRuns();
    signals.add(new ReadinessSignal("Sample size", total + " shadow runs",
        total == 0 ? "NO_DATA" : total >= 20 ? "ADEQUATE_SAMPLE" : "LIMITED_SAMPLE"));
    signals.add(new ReadinessSignal("Human correction rate", formatPercent(metrics.humanCorrectionRate()),
        metrics.reviewedShadowRuns() == 0 ? "NO_DATA"
            : metrics.humanCorrectionRate().compareTo(new BigDecimal("0.20")) <= 0 ? "STRONG"
            : metrics.humanCorrectionRate().compareTo(new BigDecimal("0.40")) <= 0 ? "MODERATE" : "NEEDS_IMPROVEMENT"));
    signals.add(new ReadinessSignal("Automation candidates", metrics.automationCandidateCount() + " runs",
        total == 0 ? "NO_DATA" : metrics.automationCandidateCount() > 0 ? "CANDIDATES_PRESENT" : "NONE_YET"));
    signals.add(new ReadinessSignal("Review workload", metrics.reviewRequiredCount() + " runs require review", "INFORMATIONAL"));
    signals.add(new ReadinessSignal("Estimated time saved", metrics.estimatedMinutesSaved() + " minutes",
        metrics.estimatedMinutesSaved().compareTo(BigDecimal.ZERO) > 0 ? "POSITIVE" : "NO_DATA"));
    return signals;
  }

  private static String formatPercent(BigDecimal rate) {
    return rate.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
  }

  @Transactional(readOnly = true)
  public List<ExceptionCategorySlice> exceptionBreakdown() {
    UUID tenantId = TenantContext.requireTenantId();
    List<ShadowRun> runs = shadowRunRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    Map<String, Long> counts = exceptionCategoryCounts(runs);
    long categorized = counts.values().stream().mapToLong(Long::longValue).sum();
    List<ExceptionCategorySlice> slices = new ArrayList<>();
    for (Map.Entry<String, Long> entry : counts.entrySet()) {
      BigDecimal percentage = categorized == 0 ? BigDecimal.ZERO
          : BigDecimal.valueOf(entry.getValue()).divide(BigDecimal.valueOf(categorized), 4, RoundingMode.HALF_UP);
      slices.add(new ExceptionCategorySlice(entry.getKey(), entry.getValue(), percentage));
    }
    return slices;
  }

  private static boolean isReviewed(ShadowRun run) {
    return run.getReviewedAt() != null || "REVIEWED".equals(run.getStatus()) || "ACCEPTED".equals(run.getStatus()) || "CORRECTED".equals(run.getStatus()) || "REJECTED".equals(run.getStatus());
  }

  private static Map<String, Long> exceptionCategoryCounts(List<ShadowRun> runs) {
    return countBy(runs, ShadowRun::getExceptionCategory);
  }

  private static BigDecimal estimatedMinutesSaved(List<ShadowRun> runs) {
    BigDecimal saved = BigDecimal.ZERO;
    for (ShadowRun run : runs) {
      BigDecimal baseline = run.getManualBaselineMinutes();
      if (baseline == null) {
        continue;
      }
      BigDecimal assisted = run.getAssistedProcessingMinutes() == null ? BigDecimal.ZERO : run.getAssistedProcessingMinutes();
      BigDecimal delta = baseline.subtract(assisted);
      if (delta.compareTo(BigDecimal.ZERO) > 0) {
        saved = saved.add(delta);
      }
    }
    return saved.setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal averageOf(List<ShadowRun> runs, Function<ShadowRun, BigDecimal> accessor) {
    List<BigDecimal> values = runs.stream().map(accessor).filter(value -> value != null).toList();
    if (values.isEmpty()) {
      return BigDecimal.ZERO;
    }
    BigDecimal total = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    return total.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
  }

  private static BigDecimal nonNegativeOrNull(BigDecimal value) {
    if (value == null) {
      return null;
    }
    return value.compareTo(BigDecimal.ZERO) < 0 ? null : value;
  }

  private static String normalizeCategory(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static void requireValue(Object value, String label) {
    if (value == null || (value instanceof String text && text.isBlank())) {
      throw new IllegalArgumentException(label + " is required");
    }
  }

  private static String statusForCorrection(String correctionType) {
    return switch (correctionType) {
      case "ACCEPTED", "SUBSTITUTION_ACCEPTED" -> "ACCEPTED";
      case "REJECTED", "SUBSTITUTION_REJECTED" -> "REJECTED";
      default -> "CORRECTED";
    };
  }

  private static BigDecimal averageConfidence(List<ShadowRun> runs) {
    List<BigDecimal> confidenceScores = runs.stream().map(ShadowRun::getConfidenceScore).filter(score -> score != null).toList();
    if (confidenceScores.isEmpty()) {
      return BigDecimal.ZERO;
    }
    BigDecimal total = confidenceScores.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    return total.divide(BigDecimal.valueOf(confidenceScores.size()), 4, RoundingMode.HALF_UP);
  }

  private static <T> Map<String, Long> countBy(List<T> values, Function<T, String> classifier) {
    return values.stream()
        .map(classifier)
        .filter(value -> value != null && !value.isBlank())
        .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
  }

  public record PilotMetrics(
      long totalShadowRuns,
      long reviewedShadowRuns,
      long acceptedCount,
      long correctedCount,
      long rejectedCount,
      BigDecimal humanCorrectionRate,
      BigDecimal averageConfidence,
      Map<String, Long> exceptionCategoryCounts,
      Map<String, Long> predictionTypeBreakdown,
      Map<String, Long> correctionTypeBreakdown,
      long automationCandidateCount,
      long reviewRequiredCount,
      BigDecimal averageManualBaselineMinutes,
      BigDecimal averageAssistedMinutes,
      BigDecimal estimatedMinutesSaved,
      BigDecimal estimatedCostSaved,
      String costCurrency) {}

  public record ExceptionCategorySlice(String category, long count, BigDecimal percentage) {}

  public record ReadinessSignal(String label, String value, String assessment) {}

  public record EvidenceReport(
      java.time.Instant reportGeneratedAt,
      UUID tenantId,
      PilotMetrics metrics,
      long totalHumanCorrections,
      List<ExceptionCategorySlice> exceptionBreakdown,
      List<ExceptionCategorySlice> topExceptionCategories,
      List<ReadinessSignal> readinessSignals,
      List<String> limitations,
      String safetyStatement) {}
}
