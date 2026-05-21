package com.orderpilot.application.services.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.connector.LocalDevelopmentSecretVaultService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.integration.*;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.datasource.url=jdbc:h2:mem:stage13_connector_sync_security;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON", "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@Import({IntegrationConnectionService.class, ConnectorSyncEventService.class, AuditEventService.class, LocalDevelopmentSecretVaultService.class, CoreConfiguration.class, DemoErpIntegrationAdapter.class})
class ConnectorSyncSecurityTest {
  @Autowired IntegrationConnectionService connectionService;
  @Autowired ConnectorSyncEventService syncEventService;
  @Autowired AuditEventRepository auditEventRepository;

  @AfterEach void clearTenant() { TenantContext.clear(); }

  @Test void readOnlySyncRecordsEventWithNoExternalWrites() {
    TenantContext.setTenantId(UUID.randomUUID());
    var connection = connectionService.createDraft(IntegrationProviderType.OTHER_ERP, "Demo ERP", "CLOUD_API", null, "mock");
    connectionService.activate(connection.getId());

    ConnectorSyncEvent event = syncEventService.runImport(connection.getId(), "PRODUCT_IMPORT");

    assertThat(event.getStatus()).isEqualTo("SUCCESS");
    assertThat(event.getRecordsRead()).isGreaterThan(0);
    assertThat(event.getRecordsWritten()).isZero();
    assertThat(auditEventRepository.findAll()).extracting("action").contains("CONNECTOR_SYNC_STARTED", "CONNECTOR_SYNC_COMPLETED");
  }
}
