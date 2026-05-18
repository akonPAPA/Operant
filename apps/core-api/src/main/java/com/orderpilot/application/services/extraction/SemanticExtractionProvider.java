package com.orderpilot.application.services.extraction;

import java.util.List;

public interface SemanticExtractionProvider {
  SemanticExtractionOutput extractStructuredData(String text, ExtractionContext context);
  String providerName();
  String schemaVersion();
  record ExtractionContext(String sourceType, String providerType) {}
  record SemanticExtractionOutput(String detectedIntent, String documentType, double providerConfidence, List<FieldCandidate> fields, List<LineItemCandidate> lineItems, List<String> warnings) {}
  record FieldCandidate(String fieldName, String rawValue, String normalizedValue, String valueType, double confidence, int startOffset, int endOffset) {}
  record LineItemCandidate(int lineNumber, String rawSku, String rawDescription, String rawQuantity, String rawUom, double confidence, int startOffset, int endOffset) {}
}