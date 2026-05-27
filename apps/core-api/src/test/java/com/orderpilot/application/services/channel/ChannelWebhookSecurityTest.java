package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.connector.LocalDevelopmentSecretVaultService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.channel.*;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.util.Map;
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
@TestPropertySource(properties = {"spring.datasource.url=jdbc:h2:mem:stage13_webhook_security;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON", "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@Import({ChannelConnectionService.class, ChannelEventNormalizationService.class, AuditEventService.class, LocalDevelopmentSecretVaultService.class, CoreConfiguration.class, ObjectMapper.class, TelegramChannelAdapter.class, TelegramWebhookVerifier.class})
class ChannelWebhookSecurityTest {
  @Autowired ChannelConnectionService connectionService;
  @Autowired ChannelEventNormalizationService normalizationService;
  @Autowired InboundChannelEventRepository eventRepository;
  @Autowired AuditEventRepository auditEventRepository;
  @Autowired DraftQuoteRepository draftQuoteRepository;
  @Autowired DraftOrderRepository draftOrderRepository;

  @AfterEach void clearTenant() { TenantContext.clear(); }

  @Test void disabledConnectionRejectsWebhook() {
    TenantContext.setTenantId(UUID.randomUUID());
    var connection = connectionService.createDraft(ChannelProviderType.TELEGRAM, "Telegram", null, null, null);

    assertThatThrownBy(() -> normalizationService.normalize(connection.getId(), ChannelProviderType.TELEGRAM, Map.of("message_id", "m1", "text", "hello")))
        .hasMessageContaining("ACTIVE");
    assertThat(eventRepository.findAll()).isEmpty();
  }

  @Test void invalidSignatureRejectsWebhookAndAuditsFailure() {
    TenantContext.setTenantId(UUID.randomUUID());
    var connection = connectionService.createDraft(ChannelProviderType.TELEGRAM, "Telegram", null, null, "vault-ref", "SIGNATURE_HEADER");
    connectionService.activate(connection.getId());

    assertThatThrownBy(() -> normalizationService.normalize(connection.getId(), ChannelProviderType.TELEGRAM, Map.of("message_id", "m2", "text", "hello"), Map.of()))
        .hasMessageContaining("verification failed");
    assertThat(eventRepository.findAll()).isEmpty();
    assertThat(auditEventRepository.findAll()).extracting("action").contains("CHANNEL_WEBHOOK_VERIFICATION_FAILED");
  }

  @Test void localDevWebhookStoresNormalizedEventOnlyAndDeduplicatesReplay() {
    TenantContext.setTenantId(UUID.randomUUID());
    var connection = connectionService.createDraft(ChannelProviderType.TELEGRAM, "Telegram", null, null, null, "DISABLED_FOR_LOCAL_DEV");
    connectionService.activate(connection.getId());
    Map<String, String> payload = Map.of("message_id", "m3", "text", "Need filters");

    InboundChannelEvent first = normalizationService.normalize(connection.getId(), ChannelProviderType.TELEGRAM, payload);
    InboundChannelEvent second = normalizationService.normalize(connection.getId(), ChannelProviderType.TELEGRAM, payload);

    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(first.getVerificationStatus()).isEqualTo("SKIPPED_LOCAL_DEV");
    assertThat(eventRepository.findAll()).hasSize(1);
    assertThat(draftQuoteRepository.count()).isZero();
    assertThat(draftOrderRepository.count()).isZero();
  }
}
