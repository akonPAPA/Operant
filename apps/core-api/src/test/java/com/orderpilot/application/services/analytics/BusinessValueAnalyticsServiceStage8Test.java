package com.orderpilot.application.services.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.Stage8Dtos.RoiAssumptionsRequest;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.analytics.RoiAssumptionsRepository;
import com.orderpilot.domain.audit.AuditEvent;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.intake.ChannelMessage;
import com.orderpilot.domain.intake.ChannelMessageRepository;
import com.orderpilot.domain.intake.InboundDocument;
import com.orderpilot.domain.intake.InboundDocumentRepository;
import com.orderpilot.domain.reconciliation.ReconciliationCase;
import com.orderpilot.domain.reconciliation.ReconciliationCaseRepository;
import com.orderpilot.domain.reconciliation.ReconciliationSeverity;
import com.orderpilot.domain.validation.DiscountCheckResult;
import com.orderpilot.domain.validation.DiscountCheckResultRepository;
import com.orderpilot.domain.validation.MarginCheckResult;
import com.orderpilot.domain.validation.MarginCheckResultRepository;
import com.orderpilot.application.services.runtime.FeatureEntitlementGuard;
import com.orderpilot.application.services.runtime.QuotaGuard;
import com.orderpilot.application.services.runtime.RateLimitService;
import com.orderpilot.application.services.runtime.RuntimeGuardService;
import com.orderpilot.application.services.runtime.UsageMeterService;
import com.orderpilot.domain.workspace.*;
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
@Import({BusinessValueAnalyticsService.class, RoiAssumptionsService.class, CommerceAnalyticsService.class, CoreConfiguration.class,
  RuntimeGuardService.class, QuotaGuard.class, RateLimitService.class, FeatureEntitlementGuard.class, UsageMeterService.class})
class BusinessValueAnalyticsServiceStage8Test {
  private static final Instant NOW = Instant.parse("2026-05-27T00:00:00Z");

  @Autowired private BusinessValueAnalyticsService valueService;
  @Autowired private RoiAssumptionsService assumptionsService;
  @Autowired private RoiAssumptionsRepository roiAssumptions;
  @Autowired private ChannelMessageRepository channelMessages;
  @Autowired private InboundDocumentRepository inboundDocuments;
  @Autowired private ExceptionCaseRepository exceptionCases;
  @Autowired private AuditEventRepository auditEvents;
  @Autowired private DraftQuoteRepository draftQuotes;
  @Autowired private DraftQuoteLineRepository draftQuoteLines;
  @Autowired private DraftOrderRepository draftOrders;
  @Autowired private DiscountCheckResultRepository discounts;
  @Autowired private MarginCheckResultRepository margins;
  @Autowired private ReconciliationCaseRepository reconciliationCases;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void defaultAssumptionsDriveEstimatedHoursAndLaborCostWhenTenantConfigIsMissing() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    channelMessages.save(new ChannelMessage(tenantId, "TELEGRAM", "m1", "chat", "sender", "Sender", null, "INBOUND", "TEXT", "Need quote", "{}", "RECEIVED", NOW));
    inboundDocuments.save(new InboundDocument(tenantId, "EMAIL", "PDF", "RECEIVED", "rfq.pdf", "application/pdf", 100L, "obj", "sha", "buyer", "RFQ", "{}", NOW));

    var summary = valueService.summary();

    assertThat(summary.defaultAssumptions()).isTrue();
    assertThat(summary.estimated()).isTrue();
    assertThat(summary.estimatedOperatorHoursSaved()).isEqualByComparingTo("0.40");
    assertThat(summary.estimatedLaborCostSaved()).isEqualByComparingTo("18.00");
    assertThat(summary.currency()).isEqualTo("USD");
    assertThat(roiAssumptions.findByTenantId(tenantId)).isEmpty();
  }

  @Test
  void putAssumptionsUpdatesOnlyCurrentTenant() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    assumptionsService.update(new RoiAssumptionsRequest(new BigDecimal("30.00"), new BigDecimal("100.00"), "KZT", "balanced"));

    var tenantAConfig = assumptionsService.current();
    TenantContext.setTenantId(tenantB);
    var tenantBConfig = assumptionsService.current();

    assertThat(tenantAConfig.defaultAssumptions()).isFalse();
    assertThat(tenantAConfig.averageManualHandlingMinutesPerRequest()).isEqualByComparingTo("30.00");
    assertThat(tenantAConfig.averageFullyLoadedOperatorHourlyCost()).isEqualByComparingTo("100.00");
    assertThat(tenantAConfig.defaultCurrency()).isEqualTo("KZT");
    assertThat(tenantBConfig.defaultAssumptions()).isTrue();
    assertThat(tenantBConfig.defaultCurrency()).isEqualTo("USD");
  }

  @Test
  void valueMetricsAreTenantScopedAndDoNotCountBotOnlyHandoffsAsRecoveredRevenue() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    UUID validationRunId = UUID.randomUUID();
    UUID extractionResultId = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    assumptionsService.update(new RoiAssumptionsRequest(new BigDecimal("30.00"), new BigDecimal("100.00"), "USD", "conservative"));
    channelMessages.save(new ChannelMessage(tenantA, "TELEGRAM", "a1", "chat", "sender", "Sender", null, "INBOUND", "TEXT", "Need quote", "{}", "RECEIVED", NOW));
    inboundDocuments.save(new InboundDocument(tenantA, "EMAIL", "PDF", "RECEIVED", "a.pdf", "application/pdf", 10L, "obj-a", "sha-a", "buyer", "RFQ", "{}", NOW));
    ExceptionCase validationCase = exceptionCases.save(new ExceptionCase(tenantA, "VAL-A", "VALIDATION_RUN", validationRunId, extractionResultId, validationRunId, null, "Validation review", "OPEN", "HIGH", "ERROR", "review", NOW.minusSeconds(10800)));
    validationCase.setStatus("RESOLVED", NOW.minusSeconds(3600));
    exceptionCases.save(validationCase);
    ExceptionCase botCase = exceptionCases.save(new ExceptionCase(tenantA, "BOT-A", "BOT_CONVERSATION", UUID.randomUUID(), null, null, null, "Bot handoff", "OPEN", "NORMAL", "INFO", "bot", NOW));
    auditEvents.save(new AuditEvent(tenantA, null, "DRAFT_PREPARATION_BLOCKED", "DRAFT_QUOTE", botCase.getId().toString(), "{}", NOW));
    DraftQuote quote = draftQuotes.save(new DraftQuote(tenantA, "Q-A", null, extractionResultId, validationRunId, validationCase.getId(), "DRAFT", "USD", null, NOW));
    quote.setTotals(new BigDecimal("200.00"), new BigDecimal("15.00"), new BigDecimal("185.00"), new BigDecimal("30.00"), NOW);
    draftQuotes.save(quote);
    draftQuoteLines.save(new DraftQuoteLine(tenantA, quote.getId(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "Recovered substitute", new BigDecimal("2.00"), "EA", new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("25.00"), "DRAFT", "VALIDATED", NOW));
    DraftOrder order = draftOrders.save(new DraftOrder(tenantA, "O-A", null, extractionResultId, validationRunId, validationCase.getId(), "DRAFT", "USD", null, NOW));
    order.setTotals(new BigDecimal("50.00"), new BigDecimal("5.00"), new BigDecimal("45.00"), new BigDecimal("20.00"), NOW);
    draftOrders.save(order);
    discounts.save(new DiscountCheckResult(tenantA, validationRunId, UUID.randomUUID(), null, UUID.randomUUID(), null, new BigDecimal("20.00"), new BigDecimal("10.00"), true, "REQUIRES_APPROVAL", NOW));
    margins.save(new MarginCheckResult(tenantA, validationRunId, UUID.randomUUID(), UUID.randomUUID(), null, new BigDecimal("80.00"), new BigDecimal("100.00"), new BigDecimal("-25.00"), BigDecimal.ZERO, BigDecimal.ZERO, true, "REQUIRES_APPROVAL", NOW));
    reconciliationCases.save(new ReconciliationCase(tenantA, UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, BigDecimal.ONE, new BigDecimal("-9.00"), ReconciliationSeverity.HIGH, "[\"STALE_INVENTORY_SNAPSHOT\"]", NOW));

    channelMessages.save(new ChannelMessage(tenantB, "WHATSAPP", "b1", "chat-b", "sender", "Sender", null, "INBOUND", "TEXT", "Other tenant", "{}", "RECEIVED", NOW));
    discounts.save(new DiscountCheckResult(tenantB, UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), null, new BigDecimal("50.00"), new BigDecimal("0.00"), true, "REQUIRES_APPROVAL", NOW));

    var summary = valueService.summary();
    var leakage = valueService.leakage();
    var export = valueService.export();

    assertThat(summary.estimatedOperatorHoursSaved()).isEqualByComparingTo("1.00");
    assertThat(summary.estimatedLaborCostSaved()).isEqualByComparingTo("100.00");
    assertThat(summary.blockedUnsafeDraftAttempts()).isEqualTo(1);
    assertThat(summary.discountLeakageCount()).isEqualTo(1);
    assertThat(summary.estimatedDiscountLeakageValue()).isEqualByComparingTo("20.00");
    assertThat(summary.marginRiskCount()).isEqualTo(1);
    assertThat(summary.estimatedMarginRiskImpact()).isEqualByComparingTo("20.00");
    assertThat(summary.substituteRecoveredRevenue()).isEqualByComparingTo("200.00");
    assertThat(summary.staleInventoryRiskCount()).isEqualTo(1);
    assertThat(leakage.exceptionCausesBreakdown()).containsEntry("VALIDATION_REVIEW", 1L).containsEntry("BOT_HANDOFF", 1L);
    assertThat(export.totalInboundRequests()).isEqualTo(2);
    assertThat(export.discountLeakageCount()).isEqualTo(1);

    TenantContext.setTenantId(tenantB);
    assertThat(valueService.summary().discountLeakageCount()).isEqualTo(1);
    assertThat(valueService.summary().substituteRecoveredRevenue()).isEqualByComparingTo("0.00");
  }
}
