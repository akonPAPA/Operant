package com.orderpilot.application.services.integration.sandbox;

import com.orderpilot.domain.integration.ConnectorSandboxExecution;
import com.orderpilot.domain.integration.ConnectorSandboxExecutionStatus;

public record ConnectorSandboxExecutionResult(
    boolean allowed,
    ConnectorSandboxExecutionStatus status,
    String reasonCode,
    String message,
    ConnectorSandboxExecution execution) {

  public static ConnectorSandboxExecutionResult fromExecution(boolean allowed, String reasonCode, String message, ConnectorSandboxExecution execution) {
    return new ConnectorSandboxExecutionResult(allowed, execution.getStatus(), reasonCode, message, execution);
  }
}
