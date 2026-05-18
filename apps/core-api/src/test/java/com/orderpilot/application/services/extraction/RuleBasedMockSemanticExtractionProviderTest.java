package com.orderpilot.application.services.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class RuleBasedMockSemanticExtractionProviderTest {
  private final RuleBasedMockSemanticExtractionProvider provider = new RuleBasedMockSemanticExtractionProvider();

  @Test
  void extractsSimpleRfqPattern() {
    var output = provider.extractStructuredData("Need 10 EA SKU-001", new SemanticExtractionProvider.ExtractionContext("CHANNEL_MESSAGE", "RULE_BASED"));

    assertThat(output.detectedIntent()).isEqualTo("RFQ");
    assertThat(output.fields()).isNotEmpty();
    assertThat(output.lineItems()).isNotEmpty();
  }
}