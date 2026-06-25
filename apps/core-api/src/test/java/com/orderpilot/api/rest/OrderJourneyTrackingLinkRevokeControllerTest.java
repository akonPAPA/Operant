package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orderpilot.api.dto.OrderJourneyDtos.RevokeTrackingLinkRequest;
import com.orderpilot.api.dto.OrderJourneyDtos.TrackingLinkRevokedDto;
import com.orderpilot.application.services.journey.OrderJourneyProjectionDrainService;
import com.orderpilot.application.services.journey.OrderJourneyProjectionPublisher;
import com.orderpilot.application.services.journey.OrderJourneyProjectorRunner;
import com.orderpilot.application.services.journey.OrderJourneyReadService;
import com.orderpilot.application.services.journey.OrderJourneyService;
import com.orderpilot.application.services.journey.OrderJourneyTrackingLinkService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.security.RequestActorResolver;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * OP-CAP-46G — operator tracking-link revocation endpoint contract.
 *
 * <p>Standalone controller test (same pattern as the public tracking controller test): the controller
 * is wired directly with mocked collaborators and the real {@link GlobalExceptionHandler}. The
 * ObjectMapper mirrors Spring Boot's lenient {@code FAIL_ON_UNKNOWN_PROPERTIES=false}, so the test
 * proves that authority/state fields sent in the body are silently ignored — the request record only
 * carries {@code reason}, the link is identified by the PATH, tenant/actor come from headers/resolver,
 * and the response is the minimal operator-safe DTO with no token/hash/id leakage. Route classification
 * and permission enforcement are owned by the dedicated security/route tests.
 */
class OrderJourneyTrackingLinkRevokeControllerTest {
  private static final UUID JOURNEY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID LINK_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID RESOLVED_ACTOR = UUID.fromString("33333333-3333-3333-3333-333333333333");

  private MockMvc mockMvc;
  private OrderJourneyTrackingLinkService trackingLinkService;
  private RequestActorResolver actorResolver;

  @BeforeEach
  void setUp() {
    trackingLinkService = mock(OrderJourneyTrackingLinkService.class);
    actorResolver = mock(RequestActorResolver.class);

    OrderJourneyController controller = new OrderJourneyController(
        mock(OrderJourneyReadService.class),
        mock(OrderJourneyService.class),
        mock(OrderJourneyProjectorRunner.class),
        mock(OrderJourneyProjectionPublisher.class),
        mock(OrderJourneyProjectionDrainService.class),
        trackingLinkService,
        actorResolver);

    ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    mockMvc = MockMvcBuilders
        .standaloneSetup(controller)
        .setControllerAdvice(new GlobalExceptionHandler(Clock.systemUTC()))
        .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
        .build();

    when(actorResolver.resolveVerifiedActor(any(), any())).thenReturn(RESOLVED_ACTOR);
  }

  @Test
  void revokeIgnoresBodyAuthorityFieldsAndUsesPathIdsAndResolvedActor() throws Exception {
    when(trackingLinkService.revoke(any(), any(), any(), any()))
        .thenReturn(new TrackingLinkRevokedDto("REVOKED", Instant.parse("2026-06-25T12:00:00Z")));

    // The body carries an allowed reason PLUS a pile of forbidden authority/state fields and even a raw
    // token / token hash. None of these may influence the revoke — only the path ids + resolved actor do.
    String maliciousBody = """
        {
          "reason": "customer asked to stop sharing",
          "token": "raw-secret-token",
          "tokenHash": "deadbeefcafefeed",
          "tenantId": "99999999-9999-9999-9999-999999999999",
          "actorId": "88888888-8888-8888-8888-888888888888",
          "userId": "77777777-7777-7777-7777-777777777777",
          "customerId": "66666666-6666-6666-6666-666666666666",
          "sourceId": "55555555-5555-5555-5555-555555555555",
          "status": "ACTIVE",
          "milestoneState": "DELIVERED",
          "evidenceLevel": "VERIFIED",
          "eta": "2030-01-01T00:00:00Z",
          "sourceType": "INTERNAL",
          "sourceRef": "wh-1",
          "actorType": "SYSTEM",
          "customerVisible": true
        }
        """;

    mockMvc.perform(post("/api/v1/order-journeys/{id}/tracking-links/{linkId}/revoke", JOURNEY_ID, LINK_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .content(maliciousBody))
        .andExpect(status().isOk());

    ArgumentCaptor<UUID> journeyCaptor = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<UUID> linkCaptor = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<RevokeTrackingLinkRequest> reqCaptor = ArgumentCaptor.forClass(RevokeTrackingLinkRequest.class);
    ArgumentCaptor<UUID> actorCaptor = ArgumentCaptor.forClass(UUID.class);
    verify(trackingLinkService).revoke(journeyCaptor.capture(), linkCaptor.capture(),
        reqCaptor.capture(), actorCaptor.capture());

    // Scope + identity come from the path and the trusted resolver, never the body.
    assertThat(journeyCaptor.getValue()).isEqualTo(JOURNEY_ID);
    assertThat(linkCaptor.getValue()).isEqualTo(LINK_ID);
    assertThat(actorCaptor.getValue()).isEqualTo(RESOLVED_ACTOR);
    // The bound request record carries ONLY the reason — every other body field was dropped at binding.
    assertThat(reqCaptor.getValue().reason()).isEqualTo("customer asked to stop sharing");
  }

  @Test
  void revokeReturnsMinimalSafeDtoWithNoTokenOrIdentifierLeakage() throws Exception {
    when(trackingLinkService.revoke(any(), any(), any(), any()))
        .thenReturn(new TrackingLinkRevokedDto("REVOKED", Instant.parse("2026-06-25T12:00:00Z")));

    mockMvc.perform(post("/api/v1/order-journeys/{id}/tracking-links/{linkId}/revoke", JOURNEY_ID, LINK_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"stop\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REVOKED"))
        .andExpect(jsonPath("$.revokedAt").value("2026-06-25T12:00:00Z"))
        // No token, hash, internal link/tenant/journey id, or reason echoed back to the operator.
        .andExpect(jsonPath("$.token").doesNotExist())
        .andExpect(jsonPath("$.tokenHash").doesNotExist())
        .andExpect(jsonPath("$.linkId").doesNotExist())
        .andExpect(jsonPath("$.tenantId").doesNotExist())
        .andExpect(jsonPath("$.journeyId").doesNotExist())
        .andExpect(jsonPath("$.reason").doesNotExist())
        .andExpect(content().string(not(containsString("tokenHash"))));
  }

  @Test
  void revokeOfUnknownLinkReturnsStructuredNotFound() throws Exception {
    when(trackingLinkService.revoke(eq(JOURNEY_ID), eq(LINK_ID), any(), any()))
        .thenThrow(new NotFoundException("Tracking link not found"));

    mockMvc.perform(post("/api/v1/order-journeys/{id}/tracking-links/{linkId}/revoke", JOURNEY_ID, LINK_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }
}
