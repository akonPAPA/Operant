package com.orderpilot.api.rest;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.Stage8Dtos.CommerceAnalyticsSummaryResponse;
import com.orderpilot.application.services.analytics.CommerceAnalyticsService;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CommerceAnalyticsController.class)
@Import(CoreConfiguration.class)
class CommerceAnalyticsControllerTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private CommerceAnalyticsService service;

  @Test
  void summaryReturnsCommerceAnalyticsDto() throws Exception {
    UUID tenantId = UUID.randomUUID();
    when(service.summary()).thenReturn(new CommerceAnalyticsSummaryResponse(tenantId, BigDecimal.ZERO, "TODO", 3, 7, 2, 1, Map.of("TELEGRAM", 9L), Instant.parse("2026-05-18T00:00:00Z")));

    mockMvc.perform(get("/api/v1/analytics/commerce/summary"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
        .andExpect(jsonPath("$.totalSalesAmount").value(0))
        .andExpect(jsonPath("$.totalOrders").value(3))
        .andExpect(jsonPath("$.totalBotRfqRequests").value(7))
        .andExpect(jsonPath("$.openReconciliationCases").value(2))
        .andExpect(jsonPath("$.highSeverityReconciliationCases").value(1))
        .andExpect(jsonPath("$.channelBreakdown.TELEGRAM").value(9));
  }
}
