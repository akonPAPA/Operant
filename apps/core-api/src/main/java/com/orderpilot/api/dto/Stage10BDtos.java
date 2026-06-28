package com.orderpilot.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Stage10BDtos {
  private Stage10BDtos() {}

  public record ShadowRunRequest(
      String sourceType,
      UUID sourceId,
      String predictionType,
      String providerLabel,
      String predictionPayloadJson,
      BigDecimal confidenceScore,
      // OP-CAP-11F pilot ROI readiness (all optional): structured evidence, never raw text.
      String exceptionCategory,
      BigDecimal manualBaselineMinutes,
      BigDecimal assistedProcessingMinutes,
      Boolean automationCandidate,
      Boolean reviewRequired) {}

  // OP-CAP-11F: responses never echo raw prediction/correction payloads or AI output.
  // Presence is surfaced as a boolean flag only.
  public record ShadowRunResponse(
      UUID id,
      String sourceType,
      UUID sourceId,
      String predictionType,
      String providerMode,
      String providerLabel,
      boolean hasPredictionPayload,
      BigDecimal confidenceScore,
      String status,
      String exceptionCategory,
      BigDecimal manualBaselineMinutes,
      BigDecimal assistedProcessingMinutes,
      boolean automationCandidate,
      boolean reviewRequired,
      Instant createdAt,
      Instant reviewedAt) {}

  public record HumanCorrectionRequest(
      String correctionType,
      String beforePayloadJson,
      String afterPayloadJson,
      String correctionReason) {}

  public record HumanCorrectionResponse(
      UUID id,
      UUID shadowRunId,
      UUID correctedByUserId,
      String correctionType,
      boolean hasBeforePayload,
      boolean hasAfterPayload,
      String correctionReason,
      Instant createdAt) {}

  public record PilotMetricResponse(
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

  public record ExceptionCategoryResponse(
      String category,
      long count,
      BigDecimal percentage) {}

  public record PilotExceptionBreakdownResponse(
      long totalCategorized,
      List<ExceptionCategoryResponse> categories) {}

  // OP-CAP-11G pilot evidence report pack. Structured, non-raw, tenant-scoped composition of
  // existing pilot metrics. Never carries raw prediction/correction payloads or object-storage internals.
  public record PilotReadinessSignal(
      String label,
      String value,
      String assessment) {}

  public record PilotEvidenceReport(
      Instant reportGeneratedAt,
      UUID tenantId,
      long totalShadowRuns,
      long totalHumanCorrections,
      BigDecimal averageManualBaselineMinutes,
      BigDecimal averageAssistedProcessingMinutes,
      BigDecimal estimatedMinutesSaved,
      BigDecimal estimatedCostSaved,
      String currency,
      long automationCandidateCount,
      long reviewRequiredCount,
      BigDecimal humanCorrectionRate,
      List<ExceptionCategoryResponse> exceptionBreakdown,
      List<ExceptionCategoryResponse> topExceptionCategories,
      List<PilotReadinessSignal> readinessSignals,
      List<String> limitations,
      String safetyStatement) {}

  // OP-CAP-11H pilot demo scenario pack. Read-only, structured, non-raw composition of the
  // evidence report into honest demo-readiness scenarios. No raw payloads/secrets/object-storage internals.
  public record PilotDemoScenarioCapabilityResponse(
      String name,
      boolean available,
      String note) {}

  public record PilotDemoScenarioEvidenceResponse(
      String label,
      String value) {}

  public record PilotDemoScenarioSafetyBoundaryResponse(
      String statement) {}

  public record PilotDemoScenarioResponse(
      String code,
      String title,
      String businessObjective,
      String primaryActorRole,
      String channelSourceType,
      String readiness,
      int readinessScore,
      List<PilotDemoScenarioCapabilityResponse> requiredCapabilities,
      List<PilotDemoScenarioEvidenceResponse> evidenceSignals,
      List<String> missingCapabilities,
      List<PilotDemoScenarioSafetyBoundaryResponse> safetyBoundaries,
      String suggestedDemoRoute,
      List<String> relatedReportLinks,
      List<String> operatorTalkingPoints) {}

  public record PilotDemoScenarioPackResponse(
      Instant reportGeneratedAt,
      UUID tenantId,
      boolean tenantHasPilotEvidence,
      List<PilotDemoScenarioResponse> scenarios,
      List<String> packLimitations,
      String safetyStatement) {}
}
