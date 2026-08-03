package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.connector.LocalDevelopmentSecretVaultService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.channel.ChannelProviderType;
import com.orderpilot.domain.channel.InboundChannelEvent;
import com.orderpilot.domain.channel.InboundChannelEventRepository;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * OP-FD-P0-001..003 — connection-owned tenant authority and fail-closed denial persistence proof (H2).
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:fd_webhook_tenant_authority;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "orderpilot.bot.telegram.webhook-secret-token=fd-telegram-secret"
    })
@Import({
  ChannelConnectionService.class,
  ChannelEventNormalizationService.class,
  WebhookIntakeConnectionResolver.class,
  WebhookVerificationAuthority.class,
  AuditEventService.class,
  LocalDevelopmentSecretVaultService.class,
  CoreConfiguration.class,
  ObjectMapper.class,
  TelegramChannelAdapter.class,
  TelegramWebhookVerifier.class
})
class ChannelWebhookTenantAuthorityTest {
  @Autowired ChannelConnectionService connectionService;
  @Autowired ChannelEventNormalizationService normalizationService;
  @Autowired InboundChannelEventRepository eventRepository;
  @Autowired AuditEventRepository auditEventRepository;
  @Autowired DraftQuoteRepository draftQuoteRepository;
  @Autowired DraftOrderRepository draftOrderRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void forgedTenantHeaderCannotPersistEventUnderWrongTenant() {
    UUID ownerTenant = UUID.randomUUID();
    UUID forgedTenant = UUID.randomUUID();
    TenantContext.setTenantId(ownerTenant);
    var connection =
        connectionService.createDraft(
            ChannelProviderType.TELEGRAM, "Telegram", null, null, "vault-ref", "PROVIDER_SPECIFIC");
    connectionService.activate(connection.getId());
    TenantContext.clear();

    // Caller presents a forged tenant header-equivalent thread context.
    TenantContext.setTenantId(forgedTenant);

    assertThatThrownBy(
            () ->
                normalizationService.normalize(
                    connection.getId(),
                    ChannelProviderType.TELEGRAM,
                    Map.of("message_id", "m-forged", "text", "hello"),
                    Map.of("x-telegram-bot-api-secret-token", "wrong-secret")))
        .isInstanceOf(WebhookAuthenticationException.class);

    assertThat(eventRepository.findAll()).isEmpty();
    assertThat(auditEventRepository.findAll())
        .extracting("action")
        .contains("CHANNEL_WEBHOOK_VERIFICATION_FAILED")
        .doesNotContain("CHANNEL_WEBHOOK_ACCEPTED");
    assertThat(draftQuoteRepository.count()).isZero();
    assertThat(draftOrderRepository.count()).isZero();
  }

  @Test
  void validSignedTelegramRequestPersistsOnceUnderConnectionTenant() {
    UUID ownerTenant = UUID.randomUUID();
    TenantContext.setTenantId(ownerTenant);
    var connection =
        connectionService.createDraft(
            ChannelProviderType.TELEGRAM, "Telegram", null, null, "vault-ref", "PROVIDER_SPECIFIC");
    connectionService.activate(connection.getId());
    TenantContext.clear();
    // No tenant context before normalize — connection resolver must establish it.
    Map<String, Object> payload = Map.of("message_id", "m-valid-1", "text", "Need filters");
    Map<String, String> headers = Map.of("x-telegram-bot-api-secret-token", "fd-telegram-secret");

    InboundChannelEvent first =
        normalizationService.normalize(connection.getId(), ChannelProviderType.TELEGRAM, payload, headers);
    InboundChannelEvent second =
        normalizationService.normalize(connection.getId(), ChannelProviderType.TELEGRAM, payload, headers);

    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(first.getTenantId()).isEqualTo(ownerTenant);
    assertThat(eventRepository.findAll()).hasSize(1);
    assertThat(auditEventRepository.findAll()).extracting("action").contains("CHANNEL_WEBHOOK_ACCEPTED");
  }

  @Test
  void wrongProviderIsDeniedWithNoMutation() {
    UUID ownerTenant = UUID.randomUUID();
    TenantContext.setTenantId(ownerTenant);
    var connection =
        connectionService.createDraft(
            ChannelProviderType.TELEGRAM, "Telegram", null, null, "vault-ref", "PROVIDER_SPECIFIC");
    connectionService.activate(connection.getId());
    TenantContext.clear();

    assertThatThrownBy(
            () ->
                normalizationService.normalize(
                    connection.getId(),
                    ChannelProviderType.WHATSAPP,
                    Map.of("message_id", "m-wrong-provider", "text", "hello"),
                    Map.of()))
        .isInstanceOf(WebhookAuthenticationException.class);

    assertThat(eventRepository.findAll()).isEmpty();
  }
}
