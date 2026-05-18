package com.orderpilot.application.services.extraction;

import java.util.UUID;

public interface TextExtractionProvider {
  boolean supports(String sourceType);
  TextExtractionOutput extractText(UUID tenantId, String sourceType, UUID sourceId);
  String providerName();
  record TextExtractionOutput(String text, String extractionMethod, String language, Integer pageCount, double qualityScore) {}
}