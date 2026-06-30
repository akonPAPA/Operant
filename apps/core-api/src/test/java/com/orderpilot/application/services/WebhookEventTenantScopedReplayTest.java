package com.orderpilot.application.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.intake.WebhookEvent;
import com.orderpilot.domain.intake.WebhookEventRepository;
import com.orderpilot.domain.tenant.Tenant;
import com.orderpilot.domain.tenant.TenantRepository;
import java.time.Instant;
import java.util.Arrays;
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
@Import(WebhookSecurityService.class)
class WebhookEventTenantScopedReplayTest {

  private static final Instant NOW = Instant.parse("2026-06-04T00:00:00Z");
  private static final String PROVIDER = "TELEGRAM";
  private static final String EXTERNAL_EVENT_ID = "evt-shared-across-tenants";

  @Autowired private WebhookSecurityService securityService;
  @Autowired private WebhookEventRepository webhookEventRepository;
  @Autowired private TenantRepository tenantRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void sameExternalWebhookEventIdCanExistAcrossTenants() {
    UUID tenantA = seedTenant();
    UUID tenantB = seedTenant();
    webhookEventRepository.saveAndFlush(event(tenantA, EXTERNAL_EVENT_ID));

    TenantContext.setTenantId(tenantB);
    assertThat(securityService.isReplay(PROVIDER, EXTERNAL_EVENT_ID)).isFalse();

    webhookEventRepository.saveAndFlush(event(tenantB, EXTERNAL_EVENT_ID));

    assertThat(webhookEventRepository.findByTenantIdOrderByReceivedAtDesc(tenantA)).hasSize(1);
    assertThat(webhookEventRepository.findByTenantIdOrderByReceivedAtDesc(tenantB)).hasSize(1);
  }

  @Test
  void duplicateExternalWebhookEventIdInsideSameTenantIsDetected() {
    UUID tenantId = seedTenant();
    webhookEventRepository.saveAndFlush(event(tenantId, EXTERNAL_EVENT_ID));
    TenantContext.setTenantId(tenantId);

    assertThat(securityService.isReplay(PROVIDER, EXTERNAL_EVENT_ID)).isTrue();
  }

  @Test
  void repositoryExposesOnlyTenantScopedExternalEventReplayLookup() {
    assertThat(Arrays.stream(WebhookEventRepository.class.getDeclaredMethods())
        .map(method -> method.getName()))
        .contains("existsByTenantIdAndProviderAndExternalEventId")
        .doesNotContain(
            "existsByProviderAndExternalEventId",
            "findByProviderAndExternalEventId",
            "findByExternalEventId");
  }

  private UUID seedTenant() {
    return tenantRepository.saveAndFlush(
        new Tenant("webhook-scope-" + UUID.randomUUID(), "Webhook Scope Test", "ACTIVE", NOW)).getId();
  }

  private WebhookEvent event(UUID tenantId, String externalEventId) {
    return new WebhookEvent(
        tenantId,
        PROVIDER,
        externalEventId,
        true,
        false,
        "{}",
        "{}",
        "ACCEPTED",
        NOW);
  }
}
