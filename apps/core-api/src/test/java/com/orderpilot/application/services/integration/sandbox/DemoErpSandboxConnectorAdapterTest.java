package com.orderpilot.application.services.integration.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.domain.integration.ConnectorCommand;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DemoErpSandboxConnectorAdapterTest {
  private final DemoErpSandboxConnectorAdapter adapter = new DemoErpSandboxConnectorAdapter();

  @Test
  void supportsDemoErpOnlyAndReturnsDeterministicDryRunResponse() {
    ConnectorCommand command = new ConnectorCommand(UUID.randomUUID(), UUID.randomUUID(), null, "DEMO_ERP", "CREATE_DRAFT_QUOTE", "idem", "{\"draftQuoteId\":\"draft-1\"}", Instant.parse("2026-05-20T00:00:00Z"));

    String payload = adapter.buildDryRunPayload(command);
    SandboxValidationResult validation = adapter.validateDryRun(command, payload);
    SandboxSimulationResult result = adapter.simulate(command, payload);

    assertThat(adapter.supports("DEMO_ERP")).isTrue();
    assertThat(adapter.supports("ONE_C")).isFalse();
    assertThat(payload).contains("\"provider\":\"demo-erp-sandbox\"");
    assertThat(payload).contains("\"externalWritePerformed\":false");
    assertThat(validation.valid()).isTrue();
    assertThat(result.success()).isTrue();
    assertThat(result.responseJson()).contains("\"status\":\"DRY_RUN_ACCEPTED\"");
    assertThat(result.responseJson()).contains("\"sandboxReference\":\"sandbox-dryrun-");
    assertThat(result.responseJson()).contains("\"externalWritePerformed\":false");
  }

  @Test
  void validationFailsBeforeSimulationForNonObjectPayload() {
    ConnectorCommand command = new ConnectorCommand(UUID.randomUUID(), UUID.randomUUID(), null, "DEMO_ERP", "CREATE_DRAFT_QUOTE", "idem", "[]", Instant.parse("2026-05-20T00:00:00Z"));

    SandboxValidationResult validation = adapter.validateDryRun(command, adapter.buildDryRunPayload(command));

    assertThat(validation.valid()).isFalse();
    assertThat(validation.errorCode()).isEqualTo("SANDBOX_PAYLOAD_INVALID");
    assertThat(validation.summaryJson()).contains("\"externalWritePerformed\":false");
  }
}
