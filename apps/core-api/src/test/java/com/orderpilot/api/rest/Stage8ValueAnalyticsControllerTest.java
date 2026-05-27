package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.Stage8Dtos.*;
import com.orderpilot.application.services.analytics.BusinessValueAnalyticsService;
import com.orderpilot.application.services.analytics.RoiAssumptionsService;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.ApiPermissionGuard;
import com.orderpilot.security.ApiPermissionInterceptor;
import com.orderpilot.security.ApiSecurityWebConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(Stage8ValueAnalyticsController.class)
@Import({CoreConfiguration.class, ApiSecurityWebConfig.class, ApiPermissionInterceptor.class, ApiPermissionGuard.class})
class Stage8ValueAnalyticsControllerTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private BusinessValueAnalyticsService valueAnalyticsService;
  @MockBean private RoiAssumptionsService assumptionsService;

  @Test
  void valueEndpointsReturnPilotRoiContracts() throws Exception {
    UUID tenantId = UUID.randomUUID();
    Instant generatedAt = Instant.parse("2026-05-27T00:00:00Z");
    RoiAssumptionsResponse assumptions = new RoiAssumptionsResponse(tenantId, new BigDecimal("12.00"), new BigDecimal("45.00"), "USD", "conservative", true, generatedAt);
    when(valueAnalyticsService.summary()).thenReturn(new Stage8ValueSummaryResponse(tenantId, new BigDecimal("2.00"), new BigDecimal("90.00"), BigDecimal.ONE, new BigDecimal("0.50"), 1, 2, new BigDecimal("30.00"), 3, new BigDecimal("40.00"), new BigDecimal("100.00"), BigDecimal.ZERO, 4, "USD", true, true, generatedAt));
    when(assumptionsService.current()).thenReturn(assumptions);
    when(assumptionsService.update(any())).thenReturn(new RoiAssumptionsResponse(tenantId, new BigDecimal("20.00"), new BigDecimal("60.00"), "EUR", "balanced", false, generatedAt));
    when(valueAnalyticsService.leakage()).thenReturn(new Stage8ValueLeakageResponse(tenantId, 2, new BigDecimal("30.00"), 3, new BigDecimal("40.00"), BigDecimal.ZERO, 4, Map.of("BOT_HANDOFF", 1L), Map.of("UNKNOWN", 1L), "USD", true, generatedAt));
    when(valueAnalyticsService.productivity()).thenReturn(new Stage8ValueProductivityResponse(tenantId, 10, new BigDecimal("20.00"), new BigDecimal("30.00"), new BigDecimal("2.00"), new BigDecimal("90.00"), BigDecimal.ONE, new BigDecimal("0.50"), 1, 1, 1, "USD", true, generatedAt));
    when(valueAnalyticsService.export()).thenReturn(new Stage8PilotRoiReportResponse(tenantId, null, generatedAt, 10, new BigDecimal("20.00"), new BigDecimal("30.00"), 1, 1, 1, 1, new BigDecimal("2.00"), new BigDecimal("90.00"), 3, 2, BigDecimal.ZERO, Map.of("BOT_HANDOFF", 1L), Map.of("UNKNOWN", 1L), assumptions, true, generatedAt));

    mockMvc.perform(get("/api/stage8/value/summary").header(ApiPermissionGuard.PERMISSIONS_HEADER, "ANALYTICS_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.estimatedOperatorHoursSaved").value(2))
        .andExpect(jsonPath("$.estimatedLaborCostSaved").value(90))
        .andExpect(jsonPath("$.defaultAssumptions").value(true));
    mockMvc.perform(get("/api/stage8/value/roi-assumptions").header(ApiPermissionGuard.PERMISSIONS_HEADER, "ANALYTICS_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.defaultCurrency").value("USD"))
        .andExpect(jsonPath("$.defaultAssumptions").value(true));
    mockMvc.perform(put("/api/stage8/value/roi-assumptions")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "ANALYTICS_READ")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"averageManualHandlingMinutesPerRequest\":20,\"averageFullyLoadedOperatorHourlyCost\":60,\"defaultCurrency\":\"EUR\",\"valueAttributionMode\":\"balanced\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.defaultCurrency").value("EUR"))
        .andExpect(jsonPath("$.defaultAssumptions").value(false));
    mockMvc.perform(get("/api/stage8/value/leakage").header(ApiPermissionGuard.PERMISSIONS_HEADER, "ANALYTICS_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.exceptionCausesBreakdown.BOT_HANDOFF").value(1));
    mockMvc.perform(get("/api/stage8/value/productivity").header(ApiPermissionGuard.PERMISSIONS_HEADER, "ANALYTICS_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalInboundRequests").value(10));
    mockMvc.perform(get("/api/stage8/value/export").header(ApiPermissionGuard.PERMISSIONS_HEADER, "ANALYTICS_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.exportable").value(true))
        .andExpect(jsonPath("$.assumptions.defaultCurrency").value("USD"));
  }
}
