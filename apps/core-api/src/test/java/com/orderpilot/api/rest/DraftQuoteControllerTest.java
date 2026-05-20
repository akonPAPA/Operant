package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.Stage11ADtos.*;
import com.orderpilot.api.dto.Stage11EDtos.*;
import com.orderpilot.application.services.workspace.RfqToDraftQuoteService;
import com.orderpilot.application.services.workspace.QuoteExternalWritePreparationService;
import com.orderpilot.application.services.workspace.SubstituteApprovalService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
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

@WebMvcTest(DraftQuoteController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class})
class DraftQuoteControllerTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @MockBean private RfqToDraftQuoteService service;
  @MockBean private SubstituteApprovalService substituteApprovalService;
  @MockBean private QuoteExternalWritePreparationService externalWritePreparationService;

  @Test
  void createsDraftQuoteFromRfqEndpoint() throws Exception {
    UUID quoteId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    when(service.createFromRfq(any())).thenReturn(new DraftQuoteResponse(quoteId, tenantId, "DQ-1", "API", null, null, null, "Acme", "NEEDS_REVIEW", "NEEDS_REVIEW", true, "USD", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Instant.parse("2026-05-20T00:00:00Z"), List.of(), List.of()));

    mockMvc.perform(post("/api/v1/quotes/drafts/from-rfq")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(new CreateDraftQuoteFromRfqRequest(UUID.randomUUID(), "OPERATOR", "API", null, null, "Acme", null, List.of(new RfqLineInput("Brake pads", "BRK-001", BigDecimal.ONE, "pcs", null))))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(quoteId.toString()))
        .andExpect(jsonPath("$.status").value("NEEDS_REVIEW"));
  }

  @Test
  void readsTenantScopedDraftQuoteEndpoint() throws Exception {
    UUID quoteId = UUID.randomUUID();
    when(service.get(quoteId)).thenReturn(new DraftQuoteResponse(quoteId, UUID.randomUUID(), "DQ-2", "API", null, null, null, null, "READY_FOR_APPROVAL", "VALIDATED", true, "USD", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, Instant.parse("2026-05-20T00:00:00Z"), List.of(), List.of()));

    mockMvc.perform(get("/api/v1/quotes/drafts/" + quoteId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("READY_FOR_APPROVAL"));
  }

  @Test
  void exposesNarrowHandoffReadinessEndpoint() throws Exception {
    UUID quoteId = UUID.randomUUID();
    when(externalWritePreparationService.checkReadiness(any(), any())).thenReturn(new QuoteHandoffResponse(quoteId, "APPROVED", "READY_FOR_HANDOFF", List.of(), null, null, null, null, "EXECUTION_DISABLED", List.of("PREPARE_HANDOFF")));

    mockMvc.perform(post("/api/v1/quotes/drafts/" + quoteId + "/handoff/readiness")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(new QuoteHandoffCommand(UUID.randomUUID(), "OPERATOR", "check"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.quoteId").value(quoteId.toString()))
        .andExpect(jsonPath("$.handoffReadinessStatus").value("READY_FOR_HANDOFF"))
        .andExpect(jsonPath("$.executionStatus").value("EXECUTION_DISABLED"));
  }
}
