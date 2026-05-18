package com.orderpilot.application.services.extraction;

import org.springframework.stereotype.Service;

@Service
public class ExtractionOutputSanitizer {
  public String sanitizeText(String value) {
    if (value == null) return null;
    return value.replace("<script", "&lt;script").replace("</script>", "&lt;/script&gt;").replace("javascript:", "");
  }
  public void validateProviderOutput(SemanticExtractionProvider.SemanticExtractionOutput output) {
    if (output == null) throw new IllegalArgumentException("Provider output is missing");
    if (output.detectedIntent() == null || output.documentType() == null) throw new IllegalArgumentException("Provider output failed schema validation");
  }
}