package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.connector.*;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.channel.ChannelProviderType;
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
@TestPropertySource(properties = {"spring.datasource.url=jdbc:h2:mem:stage13_channel_diagnostics;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON", "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@Import({ChannelConnectionService.class, AuditEventService.class, LocalDevelopmentSecretVaultService.class, CoreConfiguration.class, ObjectMapper.class, TelegramChannelAdapter.class})
class ChannelConnectionDiagnosticsTest {
  @Autowired ChannelConnectionService service;

  @AfterEach void clearTenant() { TenantContext.clear(); }

  @Test void healthCheckReturnsSafeStructuredDiagnostics() {
    TenantContext.setTenantId(UUID.randomUUID());
    var connection = service.createDraft(ChannelProviderType.TELEGRAM, "Telegram", null, null, null);

    ConnectionHealthCheckResult result = service.recordHealthCheck(connection.getId());

    assertThat(result.diagnostics()).extracting(ConnectionDiagnostic::code).contains(DiagnosticCode.SECRET_MISSING, DiagnosticCode.WEBHOOK_VERIFICATION_DISABLED, DiagnosticCode.READ_ONLY_MODE);
    assertThat(result.diagnostics()).extracting(ConnectionDiagnostic::message).allSatisfy(message -> assertThat(message).doesNotContain("token", "secret-value"));
  }
}
