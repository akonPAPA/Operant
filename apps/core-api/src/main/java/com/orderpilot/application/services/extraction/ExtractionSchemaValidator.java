package com.orderpilot.application.services.extraction;

public interface ExtractionSchemaValidator {
  void validateProviderOutput(SemanticExtractionProvider.SemanticExtractionOutput output);
}
