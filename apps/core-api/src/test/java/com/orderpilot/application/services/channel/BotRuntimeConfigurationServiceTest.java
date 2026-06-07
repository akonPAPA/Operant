package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.BotRuntimeConfigurationDtos.BotRuntimeConfigurationResponse;
import com.orderpilot.api.dto.BotRuntimeConfigurationDtos.BotRuntimeConfigurationUpdateRequest;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.IntakeValidationService;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.application.services.ProcessingJobService;
import com.orderpilot.application.services.bot.BotPolicyService;
import com.orderpilot.application.services.bot.BotResponseDraftService;
import com.orderpilot.application.services.bot.BotReviewHandoffService;
import com.orderpilot.application.services.bot.BotRuntimePolicyService;
import com.orderpilot.application.services.bot.BotRuntimeService;
import com.orderpilot.application.services.bot.BotWebhookSecurityService;
import com.orderpilot.application.services.bot.NoopTelegramOutboundTransport;
import com.orderpilot.application.services.bot.RuleBasedBotIntentClassifier;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEvent;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.channel.ChannelConnection;
import com.orderpilot.domain.channel.ChannelProviderType;
import com.orderpilot.domain.tenant.Tenant;
import com.orderpilot.domain.tenant.TenantRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    BotRuntimeConfigurationService.class,
    BotRuntimePolicyService.class,
    ChannelBotRuntimeBridgeService.class,
    ChannelRfqHandoffService.class,
    ChannelIdentityResolverService.class,
    ChannelEventNormalizationService.class,
    ChannelConnectionService.class,
    TelegramChannelAdapter.class,
    BotRuntimeService.class,
    BotResponseDraftService.class,
    BotReviewHandoffService.class,
    NoopTelegramOutboundTransport.class,
    RuleBasedBotIntentClassifier.class,
    BotPolicyService.class,
    BotWebhookSecurityService.class,
    ChannelGatewayService.class,
    ChannelIdentityService.class,
    IntakeValidationService.class,
    ProcessingJobService.class,
    AuditEventService.class,
    JsonSupport.class,
    ObjectMapper.class,
    CoreConfiguration.class
})
class BotRuntimeConfigurationServiceTest {
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  @Autowired private BotRuntimeConfigurationService configurationService;
  @Autowired private ChannelConnectionService connectionService;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private TenantRepository tenantRepository;

  @AfterEach void clearTenant() { TenantContext.clear(); }

  @Test void createsSafeDefaultForConnection() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    ChannelConnection connection = activeTelegramConnection(null);

    BotRuntimeConfigurationResponse response = configurationService.getForConnection(connection.getId());

    assertThat(response.enabled()).isTrue();
    assertThat(response.rfqCaptureMode()).isEqualTo("OPERATOR_REVIEW_ONLY");
    assertThat(response.orderStatusMode()).isEqualTo("DISABLED");
    assertThat(response.priceVisibilityPolicy()).isEqualTo("IDENTIFIED_CUSTOMER_ONLY");
    assertThat(response.unknownCustomerMode()).isEqualTo("HANDOFF");
    assertThat(response.externalExecution()).isEqualTo("DISABLED");
    assertThat(response.revision()).isEqualTo(1);
    assertThat(auditActions(tenantId)).contains("BOT_RUNTIME_CONFIG_DEFAULT_CREATED");
  }

  @Test void anotherTenantCannotReadConnectionConfiguration() {
    UUID tenantA = seedTenant();
    TenantContext.setTenantId(tenantA);
    ChannelConnection connection = activeTelegramConnection(null);

    UUID tenantB = seedTenant();
    TenantContext.setTenantId(tenantB);
    assertThatThrownBy(() -> configurationService.getForConnection(connection.getId()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test void updatePersistsAllowedFieldsAndAuditsChange() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    ChannelConnection connection = activeTelegramConnection(null);

    BotRuntimeConfigurationResponse updated = configurationService.updateForConnection(connection.getId(),
        new BotRuntimeConfigurationUpdateRequest(true, true, true, "OPERATOR_REVIEW_ONLY", "DISABLED",
            "OPERATOR_REVIEW_ONLY", "DISABLED", "HANDOFF", true, "BOT_REVIEW", 720,
            "WARN_AND_HANDOFF", "IDENTIFIED_CUSTOMER_ONLY", null, null, null));

    assertThat(updated.rfqCaptureMode()).isEqualTo("DISABLED");
    assertThat(updated.inventoryFreshnessMaxMinutes()).isEqualTo(720);
    assertThat(updated.revision()).isEqualTo(2);
    assertThat(auditActions(tenantId)).contains("BOT_RUNTIME_CONFIG_UPDATED");
  }

  @Test void invalidPolicyCombinationIsRejected() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    ChannelConnection connection = activeTelegramConnection(null);

    // price disabled but visibility not NEVER is contradictory.
    assertThatThrownBy(() -> configurationService.updateForConnection(connection.getId(),
        new BotRuntimeConfigurationUpdateRequest(null, null, null, "DISABLED", null, null, null, null, null, null, null,
            null, "IDENTIFIED_CUSTOMER_ONLY", null, null, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test void responseExposesNoSecretOrTokenFields() throws Exception {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    ChannelConnection connection = activeTelegramConnection("vault://telegram/CONFIG-TEST-REF");

    BotRuntimeConfigurationResponse response = configurationService.getForConnection(connection.getId());
    String serialized = objectMapper.writeValueAsString(response);

    assertThat(serialized).doesNotContain("vault://").doesNotContain("CONFIG-TEST-REF");
    assertThat(serialized.toLowerCase()).doesNotContain("secret").doesNotContain("token").doesNotContain("credential");
  }

  @Test void resetDefaultsRestoresSafePolicy() {
    UUID tenantId = seedTenant();
    TenantContext.setTenantId(tenantId);
    ChannelConnection connection = activeTelegramConnection(null);
    configurationService.updateForConnection(connection.getId(),
        new BotRuntimeConfigurationUpdateRequest(false, false, false, "OPERATOR_REVIEW_ONLY", "DISABLED",
            "DISABLED", "DISABLED", "REJECT", true, "BOT_REVIEW", 60, "STRICT", "NEVER", null, null, null));

    BotRuntimeConfigurationResponse reset = configurationService.resetDefaults(connection.getId());

    assertThat(reset.enabled()).isTrue();
    assertThat(reset.rfqCaptureMode()).isEqualTo("OPERATOR_REVIEW_ONLY");
    assertThat(reset.unknownCustomerMode()).isEqualTo("HANDOFF");
    assertThat(auditActions(tenantId)).contains("BOT_RUNTIME_CONFIG_RESET_DEFAULTS");
  }

  // --- helpers ---

  private UUID seedTenant() {
    return tenantRepository.save(new Tenant("cfg-" + UUID.randomUUID(), "Config Test", "ACTIVE", Instant.parse("2026-06-04T00:00:00Z"))).getId();
  }

  private ChannelConnection activeTelegramConnection(String secretRef) {
    ChannelConnection draft = connectionService.createDraft(ChannelProviderType.TELEGRAM, "Telegram", null, null, secretRef);
    return connectionService.activate(draft.getId());
  }

  private List<String> auditActions(UUID tenantId) {
    return auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId).stream().map(AuditEvent::getAction).toList();
  }
}
