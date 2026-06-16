package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffReviewView;
import com.orderpilot.application.services.validation.AiValidationHandoffReviewService;
import com.orderpilot.application.services.validation.AiValidationHandoffService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AiValidationHandoffController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, NoopApiPermissionTestConfig.class})
class AiValidationHandoffControllerTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private AiValidationHandoffService handoffService;
  @MockBean private AiValidationHandoffReviewService reviewService;

  @Test
  void readsHandoffReviewWithoutCreatingBusinessState() throws Exception {
    UUID handoffId = UUID.randomUUID();
    UUID validationId = UUID.randomUUID();
    UUID reviewId = UUID.randomUUID();
    when(reviewService.get(handoffId)).thenReturn(view(reviewId, handoffId, validationId, "IN_REVIEW", null));

    mockMvc.perform(get("/api/v1/ai-validation-handoffs/{handoffId}/review", handoffId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reviewId").value(reviewId.toString()))
        .andExpect(jsonPath("$.handoffId").value(handoffId.toString()))
        .andExpect(jsonPath("$.reviewStatus").value("IN_REVIEW"))
        .andExpect(jsonPath("$.externalExecution").value("DISABLED"));
  }

  @Test
  void recordsDecisionThroughReviewService() throws Exception {
    UUID handoffId = UUID.randomUUID();
    UUID validationId = UUID.randomUUID();
    UUID reviewId = UUID.randomUUID();
    when(reviewService.decide(eq(handoffId), any())).thenReturn(
        view(reviewId, handoffId, validationId, "DRAFT_PREPARATION_READY", "APPROVE_FOR_DRAFT_PREPARATION"));

    mockMvc.perform(post("/api/v1/ai-validation-handoffs/{handoffId}/review/decision", handoffId)
            .contentType("application/json")
            .content("{\"decision\":\"APPROVE_FOR_DRAFT_PREPARATION\",\"reasonCode\":\"VALIDATED_BY_OPERATOR\",\"reviewedBy\":\"operator-1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reviewStatus").value("DRAFT_PREPARATION_READY"))
        .andExpect(jsonPath("$.decision").value("APPROVE_FOR_DRAFT_PREPARATION"))
        .andExpect(jsonPath("$.externalExecution").value("DISABLED"));

    verify(reviewService).decide(eq(handoffId), any());
  }

  @Test
  void recordsCorrectionThroughReviewService() throws Exception {
    UUID handoffId = UUID.randomUUID();
    UUID validationId = UUID.randomUUID();
    UUID reviewId = UUID.randomUUID();
    when(reviewService.recordCorrection(eq(handoffId), any())).thenReturn(
        new AiHandoffReviewView(reviewId, handoffId, validationId, "NEEDS_HUMAN_REVIEW",
            "NEEDS_HUMAN_REVIEW", "MEDIUM", false, "CORRECTION_REQUESTED", null, null, null,
            "Customer corrected by operator", "RFQ", "ACME", 1, "operator-1", "DISABLED",
            Instant.parse("2026-06-06T00:00:00Z"), Instant.parse("2026-06-06T00:00:00Z")));

    mockMvc.perform(post("/api/v1/ai-validation-handoffs/{handoffId}/review/correction", handoffId)
            .contentType("application/json")
            .content("{\"correctionSummary\":\"Customer corrected by operator\",\"correctedIntent\":\"RFQ\",\"correctedCustomerRef\":\"ACME\",\"correctedLineCount\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reviewStatus").value("CORRECTION_REQUESTED"))
        .andExpect(jsonPath("$.correctionSummary").value("Customer corrected by operator"))
        .andExpect(jsonPath("$.correctedLineCount").value(1));

    verify(reviewService).recordCorrection(eq(handoffId), any());
  }

  private AiHandoffReviewView view(UUID reviewId, UUID handoffId, UUID validationId, String status, String decision) {
    return new AiHandoffReviewView(reviewId, handoffId, validationId, "READY_FOR_DRAFT_REVIEW",
        "READY_FOR_DRAFT_REVIEW", "LOW", true, status, decision, "VALIDATED_BY_OPERATOR",
        null, null, null, null, null, "operator-1", "DISABLED",
        Instant.parse("2026-06-06T00:00:00Z"), Instant.parse("2026-06-06T00:00:00Z"));
  }
}
