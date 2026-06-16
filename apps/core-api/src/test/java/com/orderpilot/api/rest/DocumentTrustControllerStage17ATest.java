package com.orderpilot.api.rest;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.TrustDtos.DocumentTrustRunView;
import com.orderpilot.api.dto.TrustDtos.DocumentTrustSignalView;
import com.orderpilot.application.services.trust.DocumentTrustService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.errors.NotFoundException;
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
 * OP-CAP-17A Document Trust Signal Foundation — read endpoint contract.
 */
@WebMvcTest(DocumentTrustController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, NoopApiPermissionTestConfig.class})
class DocumentTrustControllerStage17ATest {
  @Autowired private MockMvc mockMvc;
  @MockBean private DocumentTrustService trustService;

  @Test
  void returnsBoundedTrustRunView() throws Exception {
    UUID id = UUID.randomUUID();
    DocumentTrustRunView view = new DocumentTrustRunView(
        id, UUID.randomUUID(), null, "HIGH", 45, "REQUIRES_REVIEW", true, false, false, 1,
        2048L, 3, Instant.parse("2026-06-13T00:00:00Z"),
        List.of(new DocumentTrustSignalView(UUID.randomUUID(), "BANK_ACCOUNT_HOLDER_MISMATCH", "HIGH",
            "bankAccountHolder", null, "metadata:bankAccountHolder",
            "Bank account holder differs from the expected counterparty.", Instant.parse("2026-06-13T00:00:00Z"))));
    when(trustService.getRunView(id)).thenReturn(view);

    mockMvc.perform(get("/api/v1/trust/document-runs/" + id).header("X-Tenant-Id", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.riskLevel").value("HIGH"))
        .andExpect(jsonPath("$.riskScore").value(45))
        .andExpect(jsonPath("$.decisionState").value("REQUIRES_REVIEW"))
        .andExpect(jsonPath("$.requiresHumanReview").value(true))
        .andExpect(jsonPath("$.signals[0].signalCode").value("BANK_ACCOUNT_HOLDER_MISMATCH"))
        .andExpect(jsonPath("$.signals[0].fieldKey").value("bankAccountHolder"))
        .andExpect(jsonPath("$.signals[0].evidenceRef").value("metadata:bankAccountHolder"));
  }

  @Test
  void missingRunMapsToNotFound() throws Exception {
    UUID id = UUID.randomUUID();
    when(trustService.getRunView(id)).thenThrow(new NotFoundException("Document trust run not found"));

    mockMvc.perform(get("/api/v1/trust/document-runs/" + id).header("X-Tenant-Id", UUID.randomUUID().toString()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }
}
