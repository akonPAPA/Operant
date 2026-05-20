package com.orderpilot.application.services.integration.sandbox;

import com.orderpilot.domain.integration.ConnectorCommand;
import org.springframework.stereotype.Component;

@Component
public class DemoErpSandboxConnectorAdapter implements SandboxConnectorAdapter {
  @Override
  public boolean supports(String targetSystemType) {
    return "DEMO_ERP".equals(targetSystemType);
  }

  @Override
  public String buildDryRunPayload(ConnectorCommand command) {
    return "{\"provider\":\"demo-erp-sandbox\",\"mode\":\"DRY_RUN\",\"connectorCommandId\":\""
        + command.getId()
        + "\",\"operationType\":\""
        + escape(command.getOperationType())
        + "\",\"payload\":"
        + objectOrEmpty(command.getPayloadJson())
        + ",\"externalWritePerformed\":false}";
  }

  @Override
  public SandboxValidationResult validateDryRun(ConnectorCommand command, String generatedPayloadJson) {
    if (command.getPayloadJson() == null || !command.getPayloadJson().trim().startsWith("{")) {
      return SandboxValidationResult.invalid(
          "SANDBOX_PAYLOAD_INVALID",
          "Connector command payload must be a JSON object for sandbox dry-run",
          "{\"payloadObject\":false,\"externalWritePerformed\":false}",
          "[\"Dry-run stopped before sandbox simulation.\"]");
    }
    if (command.getOperationType() == null || command.getOperationType().isBlank()) {
      return SandboxValidationResult.invalid(
          "SANDBOX_OPERATION_INVALID",
          "Connector command operation type is required",
          "{\"operationTypePresent\":false,\"externalWritePerformed\":false}",
          "[\"Dry-run stopped before sandbox simulation.\"]");
    }
    return SandboxValidationResult.valid(
        "{\"payloadObject\":true,\"operationTypePresent\":true,\"adapter\":\"demo-erp-sandbox\",\"externalWritePerformed\":false}",
        "[\"Sandbox result is not evidence of provider acceptance.\",\"Review payload mapping before any future real executor exists.\"]");
  }

  @Override
  public SandboxSimulationResult simulate(ConnectorCommand command, String generatedPayloadJson) {
    String suffix = command.getId() == null ? "unpersisted" : command.getId().toString().substring(0, 8);
    return SandboxSimulationResult.success(
        "{\"status\":\"DRY_RUN_ACCEPTED\",\"sandboxReference\":\"sandbox-dryrun-"
            + suffix
            + "\",\"externalWritePerformed\":false,\"provider\":\"demo-erp-sandbox\",\"adapter\":\"DemoErpSandboxConnectorAdapter\"}",
        "[\"No external connector was called.\",\"No ERP/1C/accounting/warehouse record was created or updated.\"]");
  }

  private static String objectOrEmpty(String json) {
    String trimmed = json == null ? "" : json.trim();
    return trimmed.startsWith("{") && trimmed.endsWith("}") ? trimmed : "{}";
  }

  private static String escape(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
