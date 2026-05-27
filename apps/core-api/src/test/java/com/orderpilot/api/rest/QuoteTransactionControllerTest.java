package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.Stage12ADtos.*;
import com.orderpilot.application.services.workspace.QuoteDraftService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(QuoteTransactionController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, NoopApiPermissionTestConfig.class})
class QuoteTransactionControllerTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @MockBean private QuoteDraftService quoteDraftService;

  @Test
  void createsDraftQuoteFromRfqAtRequiredEndpoint() throws Exception {
    UUID quoteId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    when(quoteDraftService.createFromRfq(any())).thenReturn(new QuoteTransactionResponse(
        quoteId,
        "DRAFT",
        new ResolvedCustomer(UUID.randomUUID(), "CUST-1", "ACME", "Acme Parts", "RESOLVED"),
        List.of(),
        List.of(),
        List.of(),
        false,
        List.of(),
        UUID.randomUUID(),
        List.of()));

    CreateDraftQuoteFromRfqCommand request = new CreateDraftQuoteFromRfqCommand(
        tenantId,
        UUID.randomUUID(),
        "OPERATOR",
        "CUST-1",
        null,
        List.of(new RequestedItem("BRK-001", "Brake pads", BigDecimal.ONE, "EA")),
        "ALM",
        BigDecimal.ZERO,
        "api-test-1");

    mockMvc.perform(post("/api/v1/quotes/from-rfq")
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.draftQuoteId").value(quoteId.toString()))
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.approvalRequired").value(false));
  }
}
