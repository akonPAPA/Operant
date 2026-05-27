package com.orderpilot.api.rest;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.Stage8Dtos.CommerceAnalyticsSummaryResponse;
import com.orderpilot.api.dto.Stage8Dtos.AnalyticsOverviewResponse;
import com.orderpilot.api.dto.Stage8Dtos.BotAnalyticsResponse;
import com.orderpilot.api.dto.Stage8Dtos.ExtractionAnalyticsResponse;
import com.orderpilot.api.dto.Stage8Dtos.IntakeAnalyticsResponse;
import com.orderpilot.api.dto.Stage8Dtos.ReviewAnalyticsResponse;
import com.orderpilot.api.dto.Stage8Dtos.ValidationAnalyticsResponse;
import com.orderpilot.api.dto.Stage8Dtos.WorkflowHealthAnalyticsResponse;
import com.orderpilot.api.dto.Stage8Dtos.Stage8BotHandoffAnalyticsResponse;
import com.orderpilot.api.dto.Stage8Dtos.Stage8ChannelVolumeResponse;
import com.orderpilot.api.dto.Stage8Dtos.Stage8CommandCenterAnalyticsResponse;
import com.orderpilot.api.dto.Stage8Dtos.Stage8OperatorReviewAnalyticsResponse;
import com.orderpilot.application.services.analytics.CommerceAnalyticsService;
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
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({CommerceAnalyticsController.class, AnalyticsController.class, Stage8AnalyticsController.class})
@Import({CoreConfiguration.class, ApiSecurityWebConfig.class, ApiPermissionInterceptor.class, ApiPermissionGuard.class})
class CommerceAnalyticsControllerTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private CommerceAnalyticsService service;

  @Test
  void summaryReturnsCommerceAnalyticsDto() throws Exception {
    UUID tenantId = UUID.randomUUID();
    when(service.summary()).thenReturn(new CommerceAnalyticsSummaryResponse(tenantId, BigDecimal.ZERO, "TODO", 3, 7, 2, 1, Map.of("TELEGRAM", 9L), Instant.parse("2026-05-18T00:00:00Z")));

    mockMvc.perform(get("/api/v1/analytics/commerce/summary")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "ANALYTICS_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
        .andExpect(jsonPath("$.totalSalesAmount").value(0))
        .andExpect(jsonPath("$.totalOrders").value(3))
        .andExpect(jsonPath("$.totalBotRfqRequests").value(7))
        .andExpect(jsonPath("$.openReconciliationCases").value(2))
        .andExpect(jsonPath("$.highSeverityReconciliationCases").value(1))
        .andExpect(jsonPath("$.channelBreakdown.TELEGRAM").value(9));
  }

  @Test
  void overviewReturnsReadOnlyOperationalAnalyticsDto() throws Exception {
    UUID tenantId = UUID.randomUUID();
    Instant generatedAt = Instant.parse("2026-05-18T00:00:00Z");
    var intake = new IntakeAnalyticsResponse(tenantId, 1, 2, 3, 1, 2, Map.of("TELEGRAM", 2L), Map.of("PENDING", 2L), generatedAt);
    var extraction = new ExtractionAnalyticsResponse(tenantId, Map.of("SUCCEEDED", 1L), BigDecimal.valueOf(0.80), 1, 4, generatedAt);
    var validation = new ValidationAnalyticsResponse(tenantId, Map.of("NEEDS_REVIEW", 1L), Map.of("PRODUCT_NOT_FOUND", 2L), 0, 1, 0, Map.of("NEEDS_HUMAN_REVIEW", 1L), generatedAt);
    var review = new ReviewAnalyticsResponse(tenantId, Map.of("REVIEW_REQUIRED", 1L), 1, 0, 0, 0, generatedAt);
    var bot = new BotAnalyticsResponse(tenantId, Map.of("HUMAN_REVIEW", 1L), Map.of("UNKNOWN", 1L), 1, 1, 1, generatedAt);
    var health = new WorkflowHealthAnalyticsResponse(tenantId, Map.of("FAILED", 1L), 1, 0, 3, 2, generatedAt);
    when(service.overview()).thenReturn(new AnalyticsOverviewResponse(tenantId, intake, extraction, validation, review, bot, health, java.util.List.of(), generatedAt));

    mockMvc.perform(get("/api/v1/analytics/overview")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "ANALYTICS_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
        .andExpect(jsonPath("$.intake.totalChannelMessages").value(2))
        .andExpect(jsonPath("$.validation.topIssueCodes.PRODUCT_NOT_FOUND").value(2))
        .andExpect(jsonPath("$.bot.handoffCount").value(1))
        .andExpect(jsonPath("$.workflowHealth.failedJobs").value(1));
  }

  @Test
  void analyticsRequestWithExplicitInsufficientPermissionIsDeniedSafely() throws Exception {
    mockMvc.perform(get("/api/v1/analytics/overview")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "INTAKE_READ"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_POLICY_DENIED"))
        .andExpect(jsonPath("$.message").value("Missing required API permission ANALYTICS_READ"));
  }

  @Test
  void stage8AnalyticsEndpointsReturnReadOnlyCommandCenterContracts() throws Exception {
    UUID tenantId = UUID.randomUUID();
    Instant generatedAt = Instant.parse("2026-05-26T00:00:00Z");
    when(service.stage8CommandCenter()).thenReturn(new Stage8CommandCenterAnalyticsResponse(tenantId, 10, 2, 3, 1, BigDecimal.valueOf(50), BigDecimal.valueOf(20), 2, Map.of("TELEGRAM", 7L), generatedAt));
    when(service.stage8ChannelVolume()).thenReturn(new Stage8ChannelVolumeResponse(tenantId, Map.of("TELEGRAM", 7L), 10, generatedAt));
    when(service.stage8OperatorReview()).thenReturn(new Stage8OperatorReviewAnalyticsResponse(tenantId, 3, 2, 4, 1, BigDecimal.valueOf(1.5), 1, 1, generatedAt));
    when(service.stage8BotHandoffs()).thenReturn(new Stage8BotHandoffAnalyticsResponse(tenantId, 2, 1, 1, Map.of("OPEN", 1L), generatedAt));

    mockMvc.perform(get("/api/stage8/analytics/command-center").header(ApiPermissionGuard.PERMISSIONS_HEADER, "ANALYTICS_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalInboundRequests").value(10))
        .andExpect(jsonPath("$.botOnlyHandoffCount").value(2))
        .andExpect(jsonPath("$.validationBackedReviewCount").value(3))
        .andExpect(jsonPath("$.blockedUnsafeDraftAttempts").value(1))
        .andExpect(jsonPath("$.channelMix.TELEGRAM").value(7));
    mockMvc.perform(get("/api/stage8/analytics/channel-volume").header(ApiPermissionGuard.PERMISSIONS_HEADER, "ANALYTICS_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestVolumeByChannel.TELEGRAM").value(7));
    mockMvc.perform(get("/api/stage8/analytics/operator-review").header(ApiPermissionGuard.PERMISSIONS_HEADER, "ANALYTICS_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.discountRiskCount").value(1))
        .andExpect(jsonPath("$.marginRiskCount").value(1));
    mockMvc.perform(get("/api/stage8/analytics/bot-handoffs").header(ApiPermissionGuard.PERMISSIONS_HEADER, "ANALYTICS_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.blockedBotOnlyDraftPreparationCount").value(1));
  }
}
