package com.orderpilot.application.services.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.domain.integration.IntegrationConnection;
import com.orderpilot.domain.integration.IntegrationProviderType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReadOnlyConnectorPilotTest {
  @Test
  void demoErpPilotReadsCountsWithoutWrites() {
    var connection = new IntegrationConnection(UUID.randomUUID(), IntegrationProviderType.OTHER_ERP, "Demo ERP", "CLOUD_API", null, "mock", Instant.now());
    ConnectorSyncResult result = new DemoErpIntegrationAdapter().importProducts(connection);

    assertThat(result.statusCode()).isEqualTo("READ_ONLY_PILOT");
    assertThat(result.recordsRead()).isGreaterThan(0);
    assertThat(result.recordsWritten()).isZero();
  }
}
