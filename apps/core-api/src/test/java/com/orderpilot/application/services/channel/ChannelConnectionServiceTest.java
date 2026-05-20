package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.channel.ChannelConnectionRepository;
import com.orderpilot.domain.channel.ChannelProviderType;
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
@Import({ChannelConnectionService.class, AuditEventService.class, CoreConfiguration.class, ObjectMapper.class, TelegramChannelAdapter.class})
class ChannelConnectionServiceTest {
  @Autowired ChannelConnectionService service;
  @Autowired ChannelConnectionRepository repository;
  @Autowired AuditEventRepository auditEventRepository;

  @AfterEach void clearTenant() { TenantContext.clear(); }

  @Test void createsDraftWithReadOnlyDefaultAndAudits() {
    TenantContext.setTenantId(UUID.randomUUID());
    var connection = service.createDraft(ChannelProviderType.TELEGRAM, "Telegram main", "bot-1", null, "vault:telegram");
    assertThat(connection.getStatus()).isEqualTo("DRAFT");
    assertThat(connection.getMode()).isEqualTo("READ_ONLY");
    assertThat(connection.getSecretRef()).isEqualTo("vault:telegram");
    assertThat(auditEventRepository.findAll()).extracting("action").contains("CHANNEL_CONNECTION_CREATED");
  }

  @Test void activatesPausesAndDisablesValidConnection() {
    TenantContext.setTenantId(UUID.randomUUID());
    var connection = service.createDraft(ChannelProviderType.TELEGRAM, "Telegram", null, null, null);
    assertThat(service.activate(connection.getId()).getStatus()).isEqualTo("ACTIVE");
    assertThat(service.pause(connection.getId()).getStatus()).isEqualTo("PAUSED");
    assertThat(service.disable(connection.getId()).getStatus()).isEqualTo("DISABLED");
  }

  @Test void enforcesTenantBoundaryOnGet() {
    UUID ownerTenant = UUID.randomUUID();
    TenantContext.setTenantId(ownerTenant);
    var connection = service.createDraft(ChannelProviderType.EMAIL, "Email", null, null, null);
    TenantContext.setTenantId(UUID.randomUUID());
    assertThatThrownBy(() -> service.get(connection.getId())).hasMessageContaining("not found");
  }
}
