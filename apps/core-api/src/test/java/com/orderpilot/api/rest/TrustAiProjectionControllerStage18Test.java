package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.TrustAiProjectionDtos.ProcessTrustAiEventsResponse;
import com.orderpilot.application.services.trust.ProjectionQueryService;
import com.orderpilot.application.services.trust.TrustAiProjectorRuntimeService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.tenant.TenantContextFilter;
import com.orderpilot.domain.trust.ai.AiMemorySourceType;
import com.orderpilot.domain.trust.events.TrustAiDomainEvent;
import com.orderpilot.domain.trust.events.TrustAiEventStatus;
import com.orderpilot.domain.trust.events.TrustAiEventType;
import com.orderpilot.infrastructure.config.CoreConfiguration;
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
 * OP-CAP-18 Trust/AI Event Projector Runtime — endpoint contract, delegation, tenant scoping, and paging.
 */
@WebMvcTest(TrustAiProjectionController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, NoopApiPermissionTestConfig.class,
    TenantContextFilter.class})
class TrustAiProjectionControllerStage18Test {
  @Autowired private MockMvc mockMvc;
  @MockBean private TrustAiProjectorRuntimeService runtime;
  @MockBean private ProjectionQueryService queryService;

  private static final String TENANT = UUID.randomUUID().toString();

  private TrustAiDomainEvent sampleEvent(TrustAiEventStatus statusValue) {
    TrustAiDomainEvent e = new TrustAiDomainEvent(UUID.fromString(TENANT), TrustAiEventType.TRUST_RISK_DECIDED,
        AiMemorySourceType.TRUST_RISK_DECISION, UUID.randomUUID(), "DOCUMENT", UUID.randomUUID(),
        "idem-1", 1, "summary", Instant.parse("2026-06-14T12:00:00Z"), Instant.parse("2026-06-14T12:00:00Z"));
    if (statusValue == TrustAiEventStatus.PROCESSED) {
      e.markProcessed(Instant.parse("2026-06-14T12:00:00Z"));
    }
    return e;
  }

  @Test
  void processBatchDelegatesAndReturnsTally() throws Exception {
    when(runtime.processTenantBatch(any(), eq(10)))
        .thenReturn(new ProcessTrustAiEventsResponse(4, 2, 1, 1, 0, Instant.parse("2026-06-14T12:00:00Z")));

    mockMvc.perform(post("/api/v1/trust/ai-events/process?limit=10").header("X-Tenant-Id", TENANT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requested").value(4))
        .andExpect(jsonPath("$.processed").value(2))
        .andExpect(jsonPath("$.failed").value(1));

    verify(runtime).processTenantBatch(any(), eq(10));
  }

  @Test
  void processOneDelegatesAndReturnsEventDto() throws Exception {
    UUID eventId = UUID.randomUUID();
    when(runtime.processEvent(any(), eq(eventId))).thenReturn(sampleEvent(TrustAiEventStatus.PROCESSED));

    mockMvc.perform(post("/api/v1/trust/ai-events/" + eventId + "/process").header("X-Tenant-Id", TENANT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PROCESSED"))
        .andExpect(jsonPath("$.eventType").value("TRUST_RISK_DECIDED"));
  }

  @Test
  void listEventsForwardsTenantScopedFiltersAndPaging() throws Exception {
    when(queryService.listEvents(any(), eq(TrustAiEventStatus.PENDING), eq(TrustAiEventType.TRUST_RISK_DECIDED),
        any(), eq(2), eq(50))).thenReturn(List.of(sampleEvent(TrustAiEventStatus.PENDING)));

    mockMvc.perform(get("/api/v1/trust/ai-events?status=PENDING&eventType=TRUST_RISK_DECIDED&page=2&size=50")
            .header("X-Tenant-Id", TENANT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].eventType").value("TRUST_RISK_DECIDED"));

    verify(queryService).listEvents(any(), eq(TrustAiEventStatus.PENDING),
        eq(TrustAiEventType.TRUST_RISK_DECIDED), any(), eq(2), eq(50));
  }

  @Test
  void deadLetterEndpointReturnsFailureDtos() throws Exception {
    TrustAiDomainEvent dead = sampleEvent(TrustAiEventStatus.PENDING);
    dead.markDeadLettered("ProjectorError", "bounded failure", Instant.parse("2026-06-14T12:00:00Z"));
    when(queryService.listDeadLettered(any(), any(), eq(0), eq(25))).thenReturn(List.of(dead));

    mockMvc.perform(get("/api/v1/trust/ai-events/dead-letter").header("X-Tenant-Id", TENANT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].status").value("DEAD_LETTERED"))
        .andExpect(jsonPath("$[0].failureCode").value("ProjectorError"));
  }
}
