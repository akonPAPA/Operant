package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.Stage8Dtos.ReconciliationCasesResponse;
import com.orderpilot.api.dto.Stage8Dtos.ReconciliationRunResponse;
import com.orderpilot.application.services.reconciliation.InventoryReconciliationService;
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

@WebMvcTest(ReconciliationController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class})
class ReconciliationControllerTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private InventoryReconciliationService service;

  @Test
  void runInventoryReturnsExpectedDto() throws Exception {
    UUID tenantId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID caseId = UUID.randomUUID();
    when(service.runInventoryReconciliation(any(), any())).thenReturn(new ReconciliationRunResponse(tenantId, productId, locationId, new BigDecimal("116"), new BigDecimal("100"), new BigDecimal("-16"), "HIGH", "OPEN", caseId, true, Instant.parse("2026-05-18T00:00:00Z")));

    mockMvc.perform(post("/api/v1/reconciliation/inventory/run")
            .contentType("application/json")
            .content("{\"productId\":\"" + productId + "\",\"locationId\":\"" + locationId + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
        .andExpect(jsonPath("$.expectedStock").value(116))
        .andExpect(jsonPath("$.mismatchQuantity").value(-16))
        .andExpect(jsonPath("$.severity").value("HIGH"))
        .andExpect(jsonPath("$.reconciliationCaseId").value(caseId.toString()));
  }

  @Test
  void casesReturnsPagedListResponse() throws Exception {
    when(service.listCases(anyInt(), anyInt())).thenReturn(new ReconciliationCasesResponse(List.of(), 0, 50, 0, 0));

    mockMvc.perform(get("/api/v1/reconciliation/cases"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(50))
        .andExpect(jsonPath("$.totalElements").value(0));
  }
}
