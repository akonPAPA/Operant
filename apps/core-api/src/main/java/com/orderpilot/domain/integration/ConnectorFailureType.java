package com.orderpilot.domain.integration;

public enum ConnectorFailureType {
  VALIDATION_FAILED,
  AUTH_FAILED,
  TIMEOUT,
  RATE_LIMITED,
  TRANSIENT_ERROR,
  PERMANENT_ERROR,
  DUPLICATE_EXTERNAL_REQUEST,
  UNKNOWN
}
