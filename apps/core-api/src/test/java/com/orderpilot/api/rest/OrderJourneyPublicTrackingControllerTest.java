package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orderpilot.api.dto.OrderJourneyDtos.PublicOrderTrackingView;
import com.orderpilot.api.dto.OrderJourneyDtos.PublicTrackingMilestoneDto;
import com.orderpilot.application.services.journey.OrderJourneyTrackingLinkService;
import com.orderpilot.application.services.journey.PublicTrackingAbuseGuard;
import com.orderpilot.application.services.runtime.InMemoryRateLimitStore;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.errors.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * OP-CAP-46C — the public secure tracking link endpoint contract: scope is derived ONLY from the
 * opaque token in the path (no tenant header / query authority is honoured), and the response carries
 * only customer-safe fields with no internal/source/signal/identifier leakage.
 *
 * <p>This is a narrow standalone controller contract test. It wires the controller directly with a
 * mocked {@link OrderJourneyTrackingLinkService} and the real {@link GlobalExceptionHandler} via
 * {@code MockMvcBuilders.standaloneSetup}, so it does not bootstrap the Spring MVC slice, security
 * autoconfig, or filter chain. Route classification and permission behaviour remain owned by the
 * dedicated security/route tests.
 */
class OrderJourneyPublicTrackingControllerTest {
  private static final Instant FIXED = Instant.parse("2026-06-26T12:00:30Z");

  private MockMvc mockMvc;
  private OrderJourneyTrackingLinkService trackingLinkService;

  @BeforeEach
  void setUp() {
    // Generous budget (1000/window) so the OP-CAP-46C contract assertions are unaffected by hardening;
    // the dedicated abuse tests below construct a controller with a tiny budget to trigger the limit.
    setUpWithAbuseLimit(1000);
  }

  private void setUpWithAbuseLimit(long maxAttempts) {
    trackingLinkService = mock(OrderJourneyTrackingLinkService.class);

    ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    PublicTrackingAbuseGuard abuseGuard = new PublicTrackingAbuseGuard(
        new InMemoryRateLimitStore(), Clock.fixed(FIXED, ZoneOffset.UTC), 60, maxAttempts);

    mockMvc = MockMvcBuilders
        .standaloneSetup(new OrderJourneyPublicTrackingController(trackingLinkService, abuseGuard))
        .setControllerAdvice(new GlobalExceptionHandler(Clock.fixed(FIXED, ZoneOffset.UTC)))
        .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
        .build();
  }

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

  // ---- Stage 9 abuse hardening --------------------------------------------------------------------

  @Test
  void highFrequencyAttemptsAreRateLimitedWithGenericRetryAfterAndNoTokenEcho() throws Exception {
    setUpWithAbuseLimit(3);
    when(trackingLinkService.resolvePublicTracking(any()))
        .thenThrow(new NotFoundException("Tracking link not found or no longer available"));

    String token = "tracking-token-stage9";
    // Up to the budget: token-state denial (404).
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(get("/api/v1/public/order-tracking/{token}", token))
          .andExpect(status().isNotFound());
    }

    // Over the budget: generic 429 with a Retry-After. The error code and message are generic and carry
    // no token/journey/tenant detail (the JSON `path` reflects only the caller's own request URI, which
    // the existing 404 also does and the server never logs).
    mockMvc.perform(get("/api/v1/public/order-tracking/{token}", token))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.code").value("PUBLIC_TRACKING_RATE_LIMITED"))
        .andExpect(jsonPath("$.message").value("Too many tracking requests; please retry later"))
        .andExpect(jsonPath("$.message").value(not(containsString(token))));
  }

  @Test
  void rateLimitDenialIsReachedRegardlessOfTokenValiditySoItIsNotAnExistenceOracle() throws Exception {
    setUpWithAbuseLimit(2);
    // A VALID token would resolve successfully under the limit...
    PublicOrderTrackingView view = new PublicOrderTrackingView(
        "Packed",
        List.of(new PublicTrackingMilestoneDto("Packed", "COMPLETED", "VERIFIED",
            Instant.parse("2026-06-14T00:00:00Z"), null)),
        true,
        Instant.parse("2026-06-15T00:00:00Z"));
    when(trackingLinkService.resolvePublicTracking(any())).thenReturn(view);

    // Exhaust the per-client budget.
    mockMvc.perform(get("/api/v1/public/order-tracking/{token}", "valid-token"))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/public/order-tracking/{token}", "valid-token"))
        .andExpect(status().isOk());

    // ...yet once over the limit even a VALID token yields the identical generic 429 a bad token would —
    // the limiter runs before resolution, so it cannot distinguish a real token from a fake one.
    mockMvc.perform(get("/api/v1/public/order-tracking/{token}", "valid-token"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("PUBLIC_TRACKING_RATE_LIMITED"));
    mockMvc.perform(get("/api/v1/public/order-tracking/{token}", "another-unknown-token"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("PUBLIC_TRACKING_RATE_LIMITED"));
  }

  @Test
  void abuseGuardAppliesWithoutAnyTenantOrPermissionHeader() throws Exception {
    setUpWithAbuseLimit(1);
    when(trackingLinkService.resolvePublicTracking(any()))
        .thenThrow(new NotFoundException("Tracking link not found or no longer available"));

    // No X-Tenant-Id / X-OrderPilot-Permissions / actor headers are sent at all.
    mockMvc.perform(get("/api/v1/public/order-tracking/{token}", "t1"))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/v1/public/order-tracking/{token}", "t2"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("PUBLIC_TRACKING_RATE_LIMITED"));
  }
}
