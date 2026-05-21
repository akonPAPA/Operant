package com.orderpilot.application.services.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.connector.*;
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
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.datasource.url=jdbc:h2:mem:stage13_integration_diagnostics;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON", "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@Import({IntegrationConnectionService.class, AuditEventService.class, LocalDevelopmentSecretVaultService.class, CoreConfiguration.class, DemoErpIntegrationAdapter.class})
class IntegrationConnectionDiagnosticsTest {
  @Autowired IntegrationConnectionService service;

  @AfterEach void clearTenant() { TenantContext.clear(); }

  @Test void healthCheckReturnsReadOnlyDiagnosticsWithoutSecrets() {
    TenantContext.setTenantId(UUID.randomUUID());
    var connection = service.createDraft(IntegrationProviderType.OTHER_ERP, "Demo ERP", "CLOUD_API", null, "mock");

    ConnectionHealthCheckResult result = service.recordHealthCheck(connection.getId());

    assertThat(result.diagnostics()).extracting(ConnectionDiagnostic::code).contains(DiagnosticCode.SECRET_MISSING, DiagnosticCode.READ_ONLY_MODE);
    assertThat(result.diagnostics()).extracting(ConnectionDiagnostic::message).allSatisfy(message -> assertThat(message).doesNotContain("raw-token-value"));
  }
}
