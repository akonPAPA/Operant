package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.TrustAnalyticsDtos.CounterpartyTrustDashboardDto;
import com.orderpilot.api.dto.TrustAnalyticsDtos.DocumentAnomalyTrendDto;
import com.orderpilot.api.dto.TrustAnalyticsDtos.OutstandingDebtItemDto;
import com.orderpilot.api.dto.TrustAnalyticsDtos.TrustAnalyticsRebuildResponseDto;
import com.orderpilot.api.dto.TrustAnalyticsDtos.TrustRiskDistributionDto;
import com.orderpilot.api.dto.TrustAnalyticsDtos.TrustReviewQueueItemDto;
import com.orderpilot.application.services.trust.TrustAnalyticsProjectionService;
import com.orderpilot.application.services.trust.TrustAnalyticsQueryService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.tenant.TenantContextFilter;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OP-CAP-17E Trust Analytics Read Models — endpoint contract, tenant scoping, and filter/paging forwarding.
 */
@WebMvcTest(TrustAnalyticsController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, NoopApiPermissionTestConfig.class,
    TenantContextFilter.class})
class TrustAnalyticsControllerStage17ETest {
  @Autowired private MockMvc mockMvc;
  @MockBean private TrustAnalyticsQueryService queryService;
  @MockBean private TrustAnalyticsProjectionService projectionService;

  private static final String TENANT = UUID.randomUUID().toString();

  @Test
  void reviewQueueReturnsPagedTenantScopedItems() throws Exception {
    TrustReviewQueueItemDto item = new TrustReviewQueueItemDto(
        UUID.randomUUID(), UUID.randomUUID(), "DOCUMENT", UUID.randomUUID(), null, null, null,
        "HIGH", 60, "REQUIRE_APPROVAL", true, true, "PENDING", "DOCUMENT_HIGH_RISK_SIGNAL",
        "HIGH risk", Instant.parse("2026-06-14T00:00:00Z"), Instant.parse("2026-06-14T00:00:00Z"),
        Instant.parse("2026-06-14T00:00:00Z"));
    when(queryService.listReviewQueue(eq("HIGH"), any(), eq(true), eq(1), eq(10)))
        .thenReturn(List.of(item));

    mockMvc.perform(get("/api/v1/trust/analytics/review-queue?riskLevel=HIGH&blocking=true&page=1&size=10")
            .header("X-Tenant-Id", TENANT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].riskLevel").value("HIGH"))
        .andExpect(jsonPath("$[0].approvalStatus").value("PENDING"));

    verify(queryService).listReviewQueue("HIGH", null, true, 1, 10);
  }

  @Test
  void counterpartyDashboardReturnsOneDto() throws Exception {
    UUID cp = UUID.randomUUID();
    CounterpartyTrustDashboardDto dto = new CounterpartyTrustDashboardDto(
        cp, 72, "STABLE", 3, 3, 2, 1, 0, 1, 0, 1, 0, 2, 1, new BigDecimal("500.0000"), "USD",
        null, null, Instant.parse("2026-06-14T00:00:00Z"), null,
        Instant.parse("2026-06-14T00:00:00Z"), Instant.parse("2026-06-14T00:00:00Z"));
    when(queryService.getCounterpartyDashboard(eq(cp))).thenReturn(dto);

    mockMvc.perform(get("/api/v1/trust/analytics/counterparties/" + cp).header("X-Tenant-Id", TENANT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.trustScore").value(72))
        .andExpect(jsonPath("$.trustTier").value("STABLE"))
        .andExpect(jsonPath("$.primaryCurrency").value("USD"));
  }

  @Test
  void outstandingDebtFiltersByStatusAndRisk() throws Exception {
    OutstandingDebtItemDto dto = new OutstandingDebtItemDto(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null, "EXT-1",
        new BigDecimal("1000.0000"), new BigDecimal("250.0000"), new BigDecimal("750.0000"), "USD",
        null, "OVERDUE", "HIGH", 13, null, null, Instant.parse("2026-06-14T00:00:00Z"),
        Instant.parse("2026-06-14T00:00:00Z"), Instant.parse("2026-06-14T00:00:00Z"));
    when(queryService.listOutstandingDebt(eq("OVERDUE"), eq("HIGH"), any(), eq(0), eq(25)))
        .thenReturn(List.of(dto));

    mockMvc.perform(get("/api/v1/trust/analytics/outstanding-debt?status=OVERDUE&riskLevel=HIGH")
            .header("X-Tenant-Id", TENANT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].status").value("OVERDUE"))
        .andExpect(jsonPath("$[0].amountRemaining").value(750.0000));

    verify(queryService).listOutstandingDebt("OVERDUE", "HIGH", null, 0, 25);
  }

  @Test
  void documentAnomaliesFilterByPeriodAndSignal() throws Exception {
    DocumentAnomalyTrendDto dto = new DocumentAnomalyTrendDto(
        "2026-06-14", Instant.parse("2026-06-14T00:00:00Z"), Instant.parse("2026-06-15T00:00:00Z"),
        "DOCUMENT_DATE_IN_FUTURE", "HIGH", null, null, 2, 2, 0,
        Instant.parse("2026-06-14T00:00:00Z"), Instant.parse("2026-06-14T00:00:00Z"));
    when(queryService.listDocumentAnomalies(eq("2026-06-01"), eq("2026-06-14"),
        eq("DOCUMENT_DATE_IN_FUTURE"), any(), eq(25))).thenReturn(List.of(dto));

    mockMvc.perform(get("/api/v1/trust/analytics/document-anomalies"
            + "?fromPeriod=2026-06-01&toPeriod=2026-06-14&signalCode=DOCUMENT_DATE_IN_FUTURE")
            .header("X-Tenant-Id", TENANT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].signalCode").value("DOCUMENT_DATE_IN_FUTURE"))
        .andExpect(jsonPath("$[0].count").value(2));
  }

  @Test
  void riskDistributionReturnsPeriodMetrics() throws Exception {
    TrustRiskDistributionDto dto = new TrustRiskDistributionDto(
        "2026-06-14", Instant.parse("2026-06-14T00:00:00Z"), Instant.parse("2026-06-15T00:00:00Z"),
        4, 2, 1, 1, 2, 2, 1, new BigDecimal("42.50"), Instant.parse("2026-06-14T00:00:00Z"));
    when(queryService.listRiskDistribution(eq("2026-06-14"), any(), any(), eq(25)))
        .thenReturn(List.of(dto));

    mockMvc.perform(get("/api/v1/trust/analytics/risk-distribution?periodKey=2026-06-14")
            .header("X-Tenant-Id", TENANT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].criticalCount").value(1))
        .andExpect(jsonPath("$[0].overrideCount").value(1));
  }

  @Test
  void rebuildDelegatesToProjectionService() throws Exception {
    TrustAnalyticsRebuildResponseDto response = new TrustAnalyticsRebuildResponseDto(
        "2026-06-14", 3, 2, 4, 5, true, Instant.parse("2026-06-14T00:00:00Z"));
    when(projectionService.rebuildAllForTenant(any())).thenReturn(response);

    mockMvc.perform(post("/api/v1/trust/analytics/rebuild").header("X-Tenant-Id", TENANT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.periodKey").value("2026-06-14"))
        .andExpect(jsonPath("$.riskDistributionProjected").value(true));

    verify(projectionService).rebuildAllForTenant(any());
  }
}
