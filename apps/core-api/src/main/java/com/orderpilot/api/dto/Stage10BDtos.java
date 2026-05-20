package com.orderpilot.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
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
      BigDecimal confidenceScore) {}

  public record ShadowRunResponse(
      UUID id,
      String sourceType,
      UUID sourceId,
      String predictionType,
      String providerMode,
      String providerLabel,
      String predictionPayloadJson,
      BigDecimal confidenceScore,
      String status,
      Instant createdAt,
      Instant reviewedAt) {}

  public record HumanCorrectionRequest(
      UUID correctedByUserId,
      String correctionType,
      String beforePayloadJson,
      String afterPayloadJson,
      String correctionReason) {}

  public record HumanCorrectionResponse(
      UUID id,
      UUID shadowRunId,
      UUID correctedByUserId,
      String correctionType,
      String beforePayloadJson,
      String afterPayloadJson,
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
      Map<String, Long> correctionTypeBreakdown) {}
}
