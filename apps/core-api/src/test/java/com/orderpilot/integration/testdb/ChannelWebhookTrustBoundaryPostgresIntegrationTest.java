package com.orderpilot.integration.testdb;

import static com.orderpilot.support.DatabaseIntegrationTestBase.CLEAN;
import static com.orderpilot.support.DatabaseIntegrationTestBase.TENANTS;
import static com.orderpilot.support.TestTenantFixtures.TENANT_A;
import static com.orderpilot.support.TestTenantFixtures.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.application.services.channel.ChannelConnectionService;
import com.orderpilot.application.services.channel.ChannelEventNormalizationService;
import com.orderpilot.application.services.channel.WebhookAuthenticationException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.channel.ChannelProviderType;
import com.orderpilot.domain.channel.InboundChannelEvent;
import com.orderpilot.domain.channel.InboundChannelEventRepository;
import com.orderpilot.support.DatabaseIntegrationTestBase;
import com.orderpilot.support.RequiresPostgresIntegration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

/**
 * OP-FD-P0-001..003 — real PostgreSQL denial / no-mutation and valid idempotent intake proof.
 *
 * <p>Requires {@code -Dorderpilot.postgres.integration.enabled=true} and a reachable Postgres per
 * {@code application-integration-test.yml}.
 */
@Sql(scripts = {CLEAN, TENANTS})
@RequiresPostgresIntegration
@TestPropertySource(
    properties = {
      "orderpilot.bot.telegram.webhook-secret-token=fd-telegram-secret",
      "orderpilot.webhook.fixture-mode-enabled=false"
    })
class ChannelWebhookTrustBoundaryPostgresIntegrationTest extends DatabaseIntegrationTestBase {
  @Autowired private ChannelConnectionService connectionService;
  @Autowired private ChannelEventNormalizationService normalizationService;
  @Autowired private InboundChannelEventRepository eventRepository;
  @Autowired private AuditEventRepository auditEventRepository;

  @Test
  void invalidTelegramSecretCreatesNoInboundEventOrBusinessSuccessAudit() {
    TenantContext.setTenantId(TENANT_A);
    var connection =
        connectionService.createDraft(
            ChannelProviderType.TELEGRAM, "Telegram PG", null, null, "vault-ref", "PROVIDER_SPECIFIC");
    connectionService.activate(connection.getId());
    TenantContext.clear();

    long eventsBefore = eventRepository.count();
    long auditsBefore = auditEventRepository.count();

    assertThatThrownBy(
            () ->
                normalizationService.normalize(
                    connection.getId(),
                    ChannelProviderType.TELEGRAM,
                    Map.of("message_id", "pg-deny-1", "text", "hello"),
                    Map.of("x-telegram-bot-api-secret-token", "wrong-secret")))
        .isInstanceOf(WebhookAuthenticationException.class);

    assertThat(eventRepository.count()).isEqualTo(eventsBefore);
    assertThat(auditEventRepository.findAll())
        .extracting("action")
        .contains("CHANNEL_WEBHOOK_VERIFICATION_FAILED")
        .doesNotContain("CHANNEL_WEBHOOK_ACCEPTED");
    assertThat(auditEventRepository.count()).isGreaterThan(auditsBefore);
  }

  @Test
  void forgedTenantHeaderCannotPersistUnderWrongTenant_andValidSignedIsIdempotent() {
    TenantContext.setTenantId(TENANT_A);
    var connection =
        connectionService.createDraft(
            ChannelProviderType.TELEGRAM, "Telegram PG Valid", null, null, "vault-ref", "PROVIDER_SPECIFIC");
    connectionService.activate(connection.getId());
    TenantContext.clear();

    TenantContext.setTenantId(TENANT_B);
    Map<String, Object> payload = Map.of("message_id", "pg-valid-1", "text", "Need filters");
    Map<String, String> headers = Map.of("x-telegram-bot-api-secret-token", "fd-telegram-secret");

    InboundChannelEvent first =
        normalizationService.normalize(connection.getId(), ChannelProviderType.TELEGRAM, payload, headers);
    InboundChannelEvent second =
        normalizationService.normalize(connection.getId(), ChannelProviderType.TELEGRAM, payload, headers);

    assertThat(first.getTenantId()).isEqualTo(TENANT_A);
    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(eventRepository.findByTenantIdOrderByReceivedAtDesc(TENANT_A)).hasSize(1);
    assertThat(eventRepository.findByTenantIdOrderByReceivedAtDesc(TENANT_B)).isEmpty();
    assertThat(auditEventRepository.findAll()).extracting("action").contains("CHANNEL_WEBHOOK_ACCEPTED");
  }
}
