package com.orderpilot.application.services.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.integration.IntegrationProviderType;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({IntegrationConnectionService.class, ConnectorSyncEventService.class, AuditEventService.class, CoreConfiguration.class, OneCIntegrationAdapter.class})
class ConnectorSyncEventServiceTest {
  @Autowired IntegrationConnectionService connectionService;
  @Autowired ConnectorSyncEventService syncEventService;

  @AfterEach void clearTenant() { TenantContext.clear(); }

  @Test void startsCompletesAndFailsSyncEvents() {
    TenantContext.setTenantId(UUID.randomUUID());
    var connection = connectionService.activate(connectionService.createDraft(IntegrationProviderType.ONE_C, "1C", "LOCAL_AGENT", null, null).getId());
    var started = syncEventService.start(connection.getId(), "PRODUCT_IMPORT", "INBOUND");
    assertThat(started.getStatus()).isEqualTo("STARTED");
    assertThat(syncEventService.complete(started.getId(), 10, 0, 0).getStatus()).isEqualTo("SUCCESS");
    var failed = syncEventService.start(connection.getId(), "CUSTOMER_IMPORT", "INBOUND");
    assertThat(syncEventService.fail(failed.getId(), "TEST", "boom").getStatus()).isEqualTo("FAILED");
  }

  @Test void runImportRecordsCountersWithoutExternalWrites() {
    TenantContext.setTenantId(UUID.randomUUID());
    var connection = connectionService.activate(connectionService.createDraft(IntegrationProviderType.ONE_C, "1C", "LOCAL_AGENT", null, null).getId());
    var event = syncEventService.runImport(connection.getId(), "PRODUCT_IMPORT");
    assertThat(event.getStatus()).isEqualTo("SUCCESS");
    assertThat(event.getRecordsWritten()).isZero();
  }
}
