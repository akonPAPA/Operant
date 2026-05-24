package com.orderpilot.application.services.extraction;

import com.orderpilot.domain.extraction.DocumentIntent;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ExtractionOutputSanitizer implements AiOutputSanitizer, ExtractionSchemaValidator {
  private static final Set<String> ALLOWED_FIELDS = Set.of(
      "customer_hint",
      "product_sku_hint",
      "product_description",
      "quantity",
      "uom",
      "requested_date",
      "delivery_location_hint",
      "raw_line_items");

  @Override
  public String sanitizeText(String value) {
    if (value == null) return null;
    return value.replace("<script", "&lt;script").replace("</script>", "&lt;/script&gt;").replace("javascript:", "");
  }

  @Override
  public void validateProviderOutput(SemanticExtractionProvider.SemanticExtractionOutput output) {
    if (output == null) throw new IllegalArgumentException("Provider output is missing");
    if (output.detectedIntent() == null || output.documentType() == null) throw new IllegalArgumentException("Provider output failed schema validation");
    try {
      DocumentIntent.valueOf(output.detectedIntent());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Provider output uses unsupported document intent");
    }
    if (output.providerConfidence() < 0.0 || output.providerConfidence() > 1.0) throw new IllegalArgumentException("Provider confidence is out of range");
    if (output.customerHints() == null || output.fields() == null || output.lineItems() == null || output.warnings() == null) throw new IllegalArgumentException("Provider output failed schema validation");
    for (var field : output.fields()) {
      if (field.fieldName() == null || !ALLOWED_FIELDS.contains(field.fieldName())) throw new IllegalArgumentException("Provider output contains unsupported field");
      if (field.confidence() < 0.0 || field.confidence() > 1.0) throw new IllegalArgumentException("Field confidence is out of range");
      if (field.startOffset() < 0 || field.endOffset() < field.startOffset()) throw new IllegalArgumentException("Field evidence offsets are invalid");
    }
    for (var line : output.lineItems()) {
      if (line.lineNumber() < 1) throw new IllegalArgumentException("Line item number is invalid");
      if (line.confidence() < 0.0 || line.confidence() > 1.0) throw new IllegalArgumentException("Line item confidence is out of range");
      if (line.startOffset() < 0 || line.endOffset() < line.startOffset()) throw new IllegalArgumentException("Line item evidence offsets are invalid");
    }
  }
}
