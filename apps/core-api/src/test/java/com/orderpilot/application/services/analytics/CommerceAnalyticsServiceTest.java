package com.orderpilot.application.services.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.Stage8Dtos.CommerceAnalyticsSummaryResponse;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.bot.*;
import com.orderpilot.domain.reconciliation.*;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
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
@Import({CommerceAnalyticsService.class, CoreConfiguration.class})
class CommerceAnalyticsServiceTest {
  @Autowired private CommerceAnalyticsService service;
  @Autowired private BotMessageRepository botMessageRepository;
  @Autowired private BotRfqRequestRepository botRfqRequestRepository;
  @Autowired private ReconciliationCaseRepository caseRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void analyticsSummaryIsTenantIsolated() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID messageId = botMessageRepository.save(new BotMessage(tenantA, conversationId, "TELEGRAM", "a-chat", "a-msg", "Need quote", BotIntent.RFQ_REQUEST, "RECEIVED", true, Instant.parse("2026-05-18T00:00:00Z"))).getId();
    botRfqRequestRepository.save(new BotRfqRequest(tenantA, conversationId, messageId, "TELEGRAM", "Need quote", "Need quote", Instant.parse("2026-05-18T00:00:00Z")));
    caseRepository.save(new ReconciliationCase(tenantA, productId, locationId, new BigDecimal("116"), new BigDecimal("100"), new BigDecimal("-16"), ReconciliationSeverity.HIGH, "[\"stock count below expected\"]", Instant.parse("2026-05-18T00:00:00Z")));
    botMessageRepository.save(new BotMessage(tenantB, UUID.randomUUID(), "TELEGRAM", "b-chat", "b-msg", "Need quote", BotIntent.RFQ_REQUEST, "RECEIVED", true, Instant.parse("2026-05-18T00:00:00Z")));

    TenantContext.setTenantId(tenantA);
    CommerceAnalyticsSummaryResponse summary = service.summary();

    assertThat(summary.totalBotRfqRequests()).isEqualTo(1);
    assertThat(summary.openReconciliationCases()).isEqualTo(1);
    assertThat(summary.highSeverityReconciliationCases()).isEqualTo(1);
    assertThat(summary.channelBreakdown()).containsEntry("TELEGRAM", 1L);
  }
}
