package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.OrderJourneyDtos.PublicOrderTrackingView;
import com.orderpilot.api.dto.OrderJourneyDtos.PublicTrackingMilestoneDto;
import com.orderpilot.application.services.journey.OrderJourneyTrackingLinkService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContextFilter;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OP-CAP-46C — the public secure tracking link endpoint contract: scope is derived ONLY from the
 * opaque token in the path (no tenant header / query authority is honoured), and the response carries
 * only customer-safe fields with no internal/source/signal/identifier leakage.
 */
@WebMvcTest(OrderJourneyPublicTrackingController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, NoopApiPermissionTestConfig.class,
    TenantContextFilter.class})
class OrderJourneyPublicTrackingControllerTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private OrderJourneyTrackingLinkService trackingLinkService;

  @Test
  void resolvesScopeFromTokenOnlyAndIgnoresClientAuthorityHeadersAndParams() throws Exception {
    PublicOrderTrackingView view = new PublicOrderTrackingView(
        "Packed",
        List.of(new PublicTrackingMilestoneDto("Packed", "COMPLETED", "VERIFIED",
            Instant.parse("2026-06-14T00:00:00Z"), null)),
        true,
        Instant.parse("2026-06-15T00:00:00Z"));
    when(trackingLinkService.resolvePublicTracking(any())).thenReturn(view);

    mockMvc.perform(get("/api/v1/public/order-tracking/{token}", "opaque-token-123")
            // None of the following may influence resolution — scope comes from the token only.
            .header("X-Tenant-Id", UUID.randomUUID().toString())
            .param("tenantId", UUID.randomUUID().toString())
            .param("journeyId", UUID.randomUUID().toString())
            .param("status", "DELIVERED")
            .param("customerVisible", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statusLabel").value("Packed"))
        .andExpect(jsonPath("$.fulfillmentTrackingConnected").value(true))
        .andExpect(jsonPath("$.milestones[0].milestoneLabel").value("Packed"))
        .andExpect(jsonPath("$.milestones[0].milestoneState").value("COMPLETED"))
        // No internal/source/identifier fields are serialized to the buyer.
        .andExpect(jsonPath("$.milestones[0].sourceRef").doesNotExist())
        .andExpect(jsonPath("$.milestones[0].sourceType").doesNotExist())
        .andExpect(jsonPath("$.milestones[0].sortOrder").doesNotExist())
        .andExpect(jsonPath("$.milestones[0].customerVisible").doesNotExist())
        .andExpect(jsonPath("$.id").doesNotExist())
        .andExpect(jsonPath("$.tenantId").doesNotExist())
        .andExpect(jsonPath("$.journeyId").doesNotExist())
        .andExpect(jsonPath("$.riskLevel").doesNotExist())
        .andExpect(jsonPath("$.internalStatus").doesNotExist())
        .andExpect(jsonPath("$.fulfillmentSignals").doesNotExist())
        .andExpect(content().string(not(containsString("actorType"))))
        .andExpect(content().string(not(containsString("connector"))));

    // The service is invoked with exactly the path token; nothing else can override the scope.
    ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
    verify(trackingLinkService).resolvePublicTracking(tokenCaptor.capture());
    assertThat(tokenCaptor.getValue()).isEqualTo("opaque-token-123");
  }

  @Test
  void deniedTokenReturnsStructuredNotFound() throws Exception {
    when(trackingLinkService.resolvePublicTracking(any()))
        .thenThrow(new NotFoundException("Tracking link not found or no longer available"));

    mockMvc.perform(get("/api/v1/public/order-tracking/{token}", "bad-token"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("Tracking link not found or no longer available"));
  }
}
