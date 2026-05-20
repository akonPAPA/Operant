package com.orderpilot.application.services.pilot;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.pilot.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
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
  private final Clock clock;

  public PilotShadowModeService(ShadowRunRepository shadowRunRepository, HumanCorrectionRepository humanCorrectionRepository, AuditEventService auditEventService, Clock clock) {
    this.shadowRunRepository = shadowRunRepository;
    this.humanCorrectionRepository = humanCorrectionRepository;
    this.auditEventService = auditEventService;
    this.clock = clock;
  }

  @Transactional
  public ShadowRun recordShadowRun(String sourceType, UUID sourceId, String predictionType, String providerLabel, String predictionPayloadJson, BigDecimal confidenceScore) {
    UUID tenantId = TenantContext.requireTenantId();
    requireValue(sourceType, "sourceType");
    requireValue(sourceId, "sourceId");
    requireValue(predictionType, "predictionType");
    ShadowRun run = shadowRunRepository.save(new ShadowRun(tenantId, sourceType, sourceId, predictionType, providerLabel, predictionPayloadJson, confidenceScore, clock.instant()));
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
    long reviewed = runs.stream().filter(run -> run.getReviewedAt() != null || "REVIEWED".equals(run.getStatus()) || "ACCEPTED".equals(run.getStatus()) || "CORRECTED".equals(run.getStatus()) || "REJECTED".equals(run.getStatus())).count();
    BigDecimal averageConfidence = averageConfidence(runs);
    BigDecimal correctionRate = reviewed == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(corrected).divide(BigDecimal.valueOf(reviewed), 4, RoundingMode.HALF_UP);
    return new PilotMetrics(total, reviewed, accepted, corrected, rejected, correctionRate, averageConfidence, Map.of(), countBy(runs, ShadowRun::getPredictionType), countBy(corrections, HumanCorrection::getCorrectionType));
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
      Map<String, Long> correctionTypeBreakdown) {}
}
