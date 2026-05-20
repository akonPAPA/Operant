package com.orderpilot.domain.integration;

public enum ConnectorSandboxExecutionStatus {
  REQUESTED,
  POLICY_BLOCKED,
  VALIDATION_FAILED,
  READY,
  RUNNING,
  SUCCEEDED,
  FAILED,
  CANCELLED
}
