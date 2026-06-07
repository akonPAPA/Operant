package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.Stage6Dtos.DraftQuoteDetail;
import com.orderpilot.api.dto.Stage6Dtos.DraftQuoteLineView;
import com.orderpilot.application.services.workspace.ApprovalWorkflowService;
import com.orderpilot.application.services.workspace.DraftOrderService;
import com.orderpilot.application.services.workspace.DraftQuoteService;
import com.orderpilot.application.services.workspace.DraftReviewService;
import com.orderpilot.application.services.workspace.ExceptionCaseService;
import com.orderpilot.application.services.workspace.SuggestedFixService;
import com.orderpilot.application.services.workspace.WorkspaceNoteService;
import com.orderpilot.application.services.workspace.WorkspaceSummaryService;
import com.orderpilot.application.services.workspace.WorkspaceTimelineService;
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

@WebMvcTest(WorkspaceController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, NoopApiPermissionTestConfig.class})
class WorkspaceDraftReviewControllerTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @MockBean private ExceptionCaseService caseService;
  @MockBean private SuggestedFixService fixService;
  @MockBean private DraftQuoteService quoteService;
  @MockBean private DraftOrderService orderService;
  @MockBean private DraftReviewService draftReviewService;
  @MockBean private ApprovalWorkflowService approvalService;
  @MockBean private WorkspaceTimelineService timelineService;
  @MockBean private WorkspaceNoteService noteService;
  @MockBean private WorkspaceSummaryService summaryService;

  private DraftQuoteDetail detail(UUID draftId, String status) {
    DraftQuoteLineView line = new DraftQuoteLineView(UUID.randomUUID(), 1, null, "SKU-1", null, "Filter", "Filter", new BigDecimal("2"), "EA", new BigDecimal("25"), null, new BigDecimal("50"), null, "NEEDS_REVIEW", "NEEDS_REVIEW");
    return new DraftQuoteDetail(draftId, UUID.randomUUID(), UUID.randomUUID(), null, "Acme", status, "NEEDS_REVIEW", true, "USD", new BigDecimal("50"), BigDecimal.ZERO, new BigDecimal("50"), null, 1, List.of(line), "DISABLED", Instant.parse("2026-06-07T00:00:00Z"));
  }

  @Test
  void detailEndpointReturnsBoundedDto() throws Exception {
    UUID draftId = UUID.randomUUID();
    when(draftReviewService.quoteDetail(draftId)).thenReturn(detail(draftId, "DRAFT"));

    mockMvc.perform(get("/api/v1/workspace/draft-quotes/" + draftId + "/review"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.draftId").value(draftId.toString()))
        .andExpect(jsonPath("$.externalExecution").value("DISABLED"))
        .andExpect(jsonPath("$.lineCount").value(1))
        .andExpect(jsonPath("$.lines[0].uom").value("EA"));
  }

  @Test
  void correctionEndpointReturnsUpdatedBoundedDto() throws Exception {
    UUID draftId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    when(draftReviewService.correctQuoteLine(eq(draftId), eq(lineId), any())).thenReturn(detail(draftId, "NEEDS_REVIEW"));

    mockMvc.perform(patch("/api/v1/workspace/draft-quotes/" + draftId + "/lines/" + lineId)
            .contentType("application/json")
            .content("{\"quantity\":2,\"uom\":\"EA\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("NEEDS_REVIEW"))
        .andExpect(jsonPath("$.externalExecution").value("DISABLED"));
  }

  @Test
  void invalidCorrectionMapsToStructuredBadRequest() throws Exception {
    UUID draftId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    when(draftReviewService.correctQuoteLine(eq(draftId), eq(lineId), any()))
        .thenThrow(new IllegalArgumentException("Quantity must be positive"));

    mockMvc.perform(patch("/api/v1/workspace/draft-quotes/" + draftId + "/lines/" + lineId)
            .contentType("application/json")
            .content("{\"quantity\":0}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
  }
}
