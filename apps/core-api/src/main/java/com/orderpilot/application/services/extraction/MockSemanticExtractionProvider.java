package com.orderpilot.application.services.extraction;

public class MockSemanticExtractionProvider extends RuleBasedMockSemanticExtractionProvider {
  @Override
  public String providerName() {
    return "mock-semantic";
  }
}
