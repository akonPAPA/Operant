package com.orderpilot.application.services.integration.sandbox;

public record SandboxSimulationResult(boolean success, String responseJson, String warningsJson, String errorCode, String errorMessage) {
  public static SandboxSimulationResult success(String responseJson, String warningsJson) {
    return new SandboxSimulationResult(true, responseJson, warningsJson, null, null);
  }

  public static SandboxSimulationResult failed(String errorCode, String errorMessage, String responseJson, String warningsJson) {
    return new SandboxSimulationResult(false, responseJson, warningsJson, errorCode, errorMessage);
  }
}
