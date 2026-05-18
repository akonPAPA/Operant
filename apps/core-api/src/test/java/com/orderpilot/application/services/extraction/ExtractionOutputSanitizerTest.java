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
    assertThatThrownBy(() -> sanitizer.validateProviderOutput(new SemanticExtractionProvider.SemanticExtractionOutput(null, "MESSAGE", 0.1, List.of(), List.of(), List.of())))
        .isInstanceOf(IllegalArgumentException.class);
  }
}