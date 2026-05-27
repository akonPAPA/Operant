package com.orderpilot.application.services.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExtractionOutputSanitizerTest {
  private final ExtractionOutputSanitizer sanitizer = new ExtractionOutputSanitizer();

  @Test
  void removesScriptMarkers() {
    assertThat(sanitizer.sanitizeText("<script>alert(1)</script>")).doesNotContain("<script");
  }

  @Test
  void rejectsMalformedProviderOutput() {
    assertThatThrownBy(() -> sanitizer.validateProviderOutput(new SemanticExtractionProvider.SemanticExtractionOutput(null, "message", 0.1, List.of(), "email", List.of(), List.of(), List.of())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsUnsupportedIntentAndSchemaFields() {
    assertThatThrownBy(() -> sanitizer.validateProviderOutput(new SemanticExtractionProvider.SemanticExtractionOutput("CREATE_ORDER", "message", 0.8, List.of(), "email", List.of(), List.of(), List.of())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported document intent");

    assertThatThrownBy(() -> sanitizer.validateProviderOutput(new SemanticExtractionProvider.SemanticExtractionOutput("RFQ", "message", 0.8, List.of(), "email", List.of(new SemanticExtractionProvider.FieldCandidate("create_quote", "yes", "yes", "command", 0.9, 0, 3)), List.of(), List.of())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported field");
  }
}
