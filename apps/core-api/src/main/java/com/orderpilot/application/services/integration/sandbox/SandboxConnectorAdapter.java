package com.orderpilot.application.services.integration.sandbox;

import com.orderpilot.domain.integration.ConnectorCommand;

public interface SandboxConnectorAdapter {
  boolean supports(String targetSystemType);
  String buildDryRunPayload(ConnectorCommand command);
  SandboxValidationResult validateDryRun(ConnectorCommand command, String generatedPayloadJson);
  SandboxSimulationResult simulate(ConnectorCommand command, String generatedPayloadJson);
}
