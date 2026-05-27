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
import com.orderpilot.api.dto.Stage8Dtos.Stage8ProductTimelineResponse;
import com.orderpilot.api.dto.Stage8Dtos.Stage8ReconciliationRefreshResponse;
import com.orderpilot.api.dto.Stage8Dtos.Stage8ReconciliationSummaryResponse;
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

@WebMvcTest({ReconciliationController.class, Stage8ReconciliationController.class})
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, NoopApiPermissionTestConfig.class})
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

  @Test
  void stage8ReconciliationEndpointsReturnReadModelContracts() throws Exception {
    UUID tenantId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID caseId = UUID.randomUUID();
    Instant generatedAt = Instant.parse("2026-05-26T00:00:00Z");
    when(service.summary()).thenReturn(new Stage8ReconciliationSummaryResponse(tenantId, 2, 1, 3, 4, 2, 5, List.of(), generatedAt));
    when(service.listCases(anyInt(), anyInt())).thenReturn(new ReconciliationCasesResponse(List.of(), 0, 50, 0, 0));
    when(service.getCase(caseId)).thenReturn(new com.orderpilot.api.dto.Stage8Dtos.ReconciliationCaseResponse(caseId, tenantId, productId, locationId, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.valueOf(-9), "HIGH", "OPEN", "[\"MISSING_STOCK_MOVEMENT\"]", generatedAt, generatedAt, generatedAt));
    when(service.productTimeline(productId)).thenReturn(new Stage8ProductTimelineResponse(tenantId, productId, List.of(), generatedAt));
    when(service.refreshProjections()).thenReturn(new Stage8ReconciliationRefreshResponse(tenantId, 1, 1, 0, false, false, generatedAt));

    mockMvc.perform(get("/api/stage8/reconciliation/summary"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.inventoryMismatchCount").value(2))
        .andExpect(jsonPath("$.highSeverityDiscrepancyCount").value(1))
        .andExpect(jsonPath("$.staleInventoryCount").value(3))
        .andExpect(jsonPath("$.lowStockCount").value(4));
    mockMvc.perform(get("/api/stage8/reconciliation/cases"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    mockMvc.perform(get("/api/stage8/reconciliation/cases/" + caseId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.likelyCauses").value("[\"MISSING_STOCK_MOVEMENT\"]"));
    mockMvc.perform(get("/api/stage8/reconciliation/products/" + productId + "/timeline"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.productId").value(productId.toString()));
    mockMvc.perform(post("/api/stage8/reconciliation/refresh"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.inventoryMutated").value(false))
        .andExpect(jsonPath("$.connectorCommandsCreated").value(false));
  }
}
