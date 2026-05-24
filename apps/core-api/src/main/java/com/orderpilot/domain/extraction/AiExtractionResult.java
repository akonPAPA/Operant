package com.orderpilot.domain.extraction;

import java.math.BigDecimal;
import java.util.UUID;

public record AiExtractionResult(
    UUID id,
    UUID tenantId,
    UUID extractionRunId,
    ExtractionSourceType sourceType,
    UUID sourceId,
    DocumentIntent detectedIntent,
    BigDecimal overallConfidence,
    String validationStatus,
    String resultJson
) {
  public static AiExtractionResult from(ExtractionResult result) {
    return new AiExtractionResult(
        result.getId(),
        result.getTenantId(),
        result.getExtractionRunId(),
        ExtractionSourceType.valueOf(result.getSourceType()),
        result.getSourceId(),
        DocumentIntent.valueOf(result.getDetectedIntent()),
        result.getOverallConfidence(),
        result.getValidationStatus(),
        result.getResultJson());
  }
}
