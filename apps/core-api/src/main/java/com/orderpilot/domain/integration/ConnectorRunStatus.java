package com.orderpilot.domain.integration;

public enum ConnectorRunStatus {
  PENDING,
  RUNNING,
  SUCCEEDED,
  FAILED,
  RETRY_SCHEDULED,
  CANCELLED
}
