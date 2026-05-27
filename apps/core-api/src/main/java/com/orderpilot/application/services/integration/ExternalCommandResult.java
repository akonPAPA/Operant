package com.orderpilot.application.services.integration;

import com.orderpilot.domain.integration.ConnectorFailureType;

public record ExternalCommandResult(boolean success, String externalReference, String statusCode, String message, ConnectorFailureType failureType, boolean retryable) {
  public static ExternalCommandResult success(String externalReference, String statusCode, String message) {
    return new ExternalCommandResult(true, externalReference, statusCode, message, null, false);
  }

  public static ExternalCommandResult failure(String statusCode, String message, ConnectorFailureType failureType, boolean retryable) {
    return new ExternalCommandResult(false, null, statusCode, message, failureType, retryable);
  }
}
