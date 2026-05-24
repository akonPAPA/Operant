package com.orderpilot.application.services.extraction;

import java.util.UUID;

public class MockTextExtractionProvider implements TextExtractionProvider {
  @Override
  public boolean supports(String sourceType) {
    return true;
  }

  @Override
  public TextExtractionOutput extractText(UUID tenantId, String sourceType, UUID sourceId) {
    return new TextExtractionOutput("Customer: Acme\nNeed 10 EA SKU-001 ship to Almaty by 2026-06-01", "MOCK_TEXT", "en", 1, 0.90);
  }

  @Override
  public String providerName() {
    return "mock-text";
  }
}
