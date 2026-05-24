package com.orderpilot.domain.extraction;

import java.util.UUID;

public record AiExtractedLineItem(
    UUID id,
    UUID tenantId,
    UUID extractionResultId,
    int lineNumber,
    String rawSku,
    String rawDescription,
    String rawQuantity,
    String rawUom,
    ConfidenceSignal confidence,
    UUID sourceEvidenceId,
    String validationStatus
) {
  public static AiExtractedLineItem from(ExtractedLineItem line) {
    return new AiExtractedLineItem(
        line.getId(),
        line.getTenantId(),
        line.getExtractionResultId(),
        line.getLineNumber(),
        line.getRawSku(),
        line.getRawDescription(),
        line.getRawQuantity(),
        line.getRawUom(),
        ConfidenceSignal.fromScore(line.getConfidence(), "line_item_extraction"),
        line.getSourceEvidenceId(),
        line.getValidationStatus());
  }
}
