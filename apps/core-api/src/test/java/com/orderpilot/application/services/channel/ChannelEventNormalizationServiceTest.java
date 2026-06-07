package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.channel.*;
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
@Import({ChannelConnectionService.class, ChannelEventNormalizationService.class, AuditEventService.class, CoreConfiguration.class, ObjectMapper.class, TelegramChannelAdapter.class, WhatsAppChannelAdapter.class, WeChatChannelAdapter.class})
class ChannelEventNormalizationServiceTest {
  @Autowired ChannelConnectionService connectionService;
  @Autowired ChannelEventNormalizationService normalizationService;

  @AfterEach void clearTenant() { TenantContext.clear(); }

  @Test void normalizesTelegramPayload() throws Exception {
    TenantContext.setTenantId(UUID.randomUUID());
    var connection = serviceConnection(ChannelProviderType.TELEGRAM);
    var payload = new ObjectMapper().readTree("{\"message\":{\"message_id\":\"tg-1\",\"chat\":{\"id\":\"cust-1\"},\"text\":\"Need filters\"}}");
    var event = normalizationService.normalize(connection.getId(), ChannelProviderType.TELEGRAM, payload);
    assertThat(event.getNormalizedText()).isEqualTo("Need filters");
    assertThat(event.getStatus()).isEqualTo("NORMALIZED");
  }

  @Test void normalizesWhatsAppPayloadAndDeduplicatesExternalId() throws Exception {
    TenantContext.setTenantId(UUID.randomUUID());
    var connection = serviceConnection(ChannelProviderType.WHATSAPP);
    var payload = new ObjectMapper().readTree("{\"id\":\"wa-1\",\"from\":\"7700\",\"text\":\"Need brake pads\"}");
    var first = normalizationService.normalize(connection.getId(), ChannelProviderType.WHATSAPP, payload);
    var duplicate = normalizationService.normalize(connection.getId(), ChannelProviderType.WHATSAPP, payload);
    assertThat(duplicate.getId()).isEqualTo(first.getId());
  }

  @Test void normalizesWeChatStubWithoutBusinessAction() throws Exception {
    TenantContext.setTenantId(UUID.randomUUID());
    var connection = serviceConnection(ChannelProviderType.WECHAT);
    var payload = new ObjectMapper().readTree("{\"event_id\":\"wx-1\",\"sender\":\"wx-customer\",\"text\":\"Need OEM 123\"}");
    var event = normalizationService.normalize(connection.getId(), ChannelProviderType.WECHAT, payload);
    assertThat(event.getProviderType()).isEqualTo(ChannelProviderType.WECHAT);
    assertThat(event.getNormalizedText()).isEqualTo("Need OEM 123");
  }

  @Test void listRecentHonorsRequestedLimitAndIsTenantScoped() throws Exception {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    var connection = serviceConnection(ChannelProviderType.TELEGRAM);
    for (int i = 1; i <= 3; i++) {
      var payload = new ObjectMapper().readTree(
          "{\"message\":{\"message_id\":\"lr-" + i + "\",\"chat\":{\"id\":\"c-" + i + "\"},\"text\":\"need part " + i + "\"}}");
      normalizationService.normalize(connection.getId(), ChannelProviderType.TELEGRAM, payload);
    }
    // A smaller requested limit is honored.
    assertThat(normalizationService.listRecent(2)).hasSize(2);
    // null falls back to the safe default window (>= the 3 seeded events).
    assertThat(normalizationService.listRecent(null)).hasSize(3);

    // A different tenant has its own (empty) recent window.
    TenantContext.setTenantId(UUID.randomUUID());
    assertThat(normalizationService.listRecent(null)).isEmpty();
  }

  @Test void clampLimitFallsBackToDefaultAndCapsAtMax() {
    assertThat(ChannelEventNormalizationService.clampLimit(null))
        .isEqualTo(ChannelEventNormalizationService.DEFAULT_EVENT_LIMIT);
    assertThat(ChannelEventNormalizationService.clampLimit(0))
        .isEqualTo(ChannelEventNormalizationService.DEFAULT_EVENT_LIMIT);
    assertThat(ChannelEventNormalizationService.clampLimit(-5))
        .isEqualTo(ChannelEventNormalizationService.DEFAULT_EVENT_LIMIT);
    assertThat(ChannelEventNormalizationService.clampLimit(10)).isEqualTo(10);
    assertThat(ChannelEventNormalizationService.clampLimit(1_000_000))
        .isEqualTo(ChannelEventNormalizationService.MAX_EVENT_LIMIT);
  }

  private com.orderpilot.domain.channel.ChannelConnection serviceConnection(ChannelProviderType providerType) {
    var c = connectionService.createDraft(providerType, providerType.name(), null, null, null);
    return connectionService.activate(c.getId());
  }
}
