package com.orderpilot.application.services.integration.sandbox;

public record SandboxValidationResult(boolean valid, String summaryJson, String warningsJson, String errorCode, String errorMessage) {
  public static SandboxValidationResult valid(String summaryJson, String warningsJson) {
    return new SandboxValidationResult(true, summaryJson, warningsJson, null, null);
  }

  public static SandboxValidationResult invalid(String errorCode, String errorMessage, String summaryJson, String warningsJson) {
    return new SandboxValidationResult(false, summaryJson, warningsJson, errorCode, errorMessage);
  }
}
