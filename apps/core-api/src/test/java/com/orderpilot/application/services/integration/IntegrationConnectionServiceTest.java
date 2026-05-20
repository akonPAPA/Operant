package com.orderpilot.application.services.integration;

import static org.assertj.core.api.Assertions.*;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
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
@Import({IntegrationConnectionService.class, AuditEventService.class, CoreConfiguration.class, OneCIntegrationAdapter.class})
class IntegrationConnectionServiceTest {
  @Autowired IntegrationConnectionService service;
  @Autowired AuditEventRepository auditEventRepository;

  @AfterEach void clearTenant() { TenantContext.clear(); }

  @Test void createsDraftWithReadOnlyDefault() {
    TenantContext.setTenantId(UUID.randomUUID());
    var connection = service.createDraft(IntegrationProviderType.ONE_C, "1C mirror", "LOCAL_AGENT", "vault:one-c", "agent:warehouse");
    assertThat(connection.getStatus()).isEqualTo("DRAFT");
    assertThat(connection.getMode()).isEqualTo("READ_ONLY");
    assertThat(connection.getConnectionKind()).isEqualTo("LOCAL_AGENT");
    assertThat(auditEventRepository.findAll()).extracting("action").contains("INTEGRATION_CONNECTION_CREATED");
  }

  @Test void activatesPausesAndDisablesValidConnection() {
    TenantContext.setTenantId(UUID.randomUUID());
    var connection = service.createDraft(IntegrationProviderType.GENERIC_DATABASE, "Read replica", "DATABASE_READONLY", null, "db-ref");
    assertThat(service.activate(connection.getId()).getStatus()).isEqualTo("ACTIVE");
    assertThat(service.pause(connection.getId()).getStatus()).isEqualTo("PAUSED");
    assertThat(service.disable(connection.getId()).getStatus()).isEqualTo("DISABLED");
  }
}
