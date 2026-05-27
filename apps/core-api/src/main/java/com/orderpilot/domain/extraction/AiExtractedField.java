package com.orderpilot.domain.extraction;

import java.math.BigDecimal;
import java.util.UUID;

public record AiExtractedField(
    UUID id,
    UUID tenantId,
    UUID extractionResultId,
    String fieldName,
    String rawValue,
    String normalizedValue,
    ConfidenceSignal confidence,
    UUID sourceEvidenceId,
    String validationStatus
) {
  public static AiExtractedField from(ExtractedField field) {
    return new AiExtractedField(
        field.getId(),
        field.getTenantId(),
        field.getExtractionResultId(),
        field.getFieldName(),
        field.getRawValue(),
        field.getNormalizedValue(),
        ConfidenceSignal.fromScore(field.getConfidence(), "field_extraction"),
        field.getSourceEvidenceId(),
        field.getValidationStatus());
  }
}
