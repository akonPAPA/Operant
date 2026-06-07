package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.Stage6Dtos.DraftPreparationResult;
import com.orderpilot.application.services.workspace.DraftCommandPreparationService;
import com.orderpilot.application.services.workspace.ValidationReviewService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ValidationReviewController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, NoopApiPermissionTestConfig.class})
class ValidationReviewPrepareDraftControllerTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @MockBean private ValidationReviewService reviewService;
  @MockBean private DraftCommandPreparationService draftCommandPreparationService;

  @Test
  void prepareDraftReturnsBoundedResult() throws Exception {
    UUID reviewCaseId = UUID.randomUUID();
    UUID draftId = UUID.randomUUID();
    when(draftCommandPreparationService.prepareDraft(eq(reviewCaseId), any()))
        .thenReturn(new DraftPreparationResult("QUOTE", draftId, reviewCaseId, "DRAFT", true, false, "DISABLED", "OPEN_OPERATOR_WORKSPACE"));

    mockMvc.perform(post("/api/v1/validation-review/" + reviewCaseId + "/prepare-draft")
            .contentType("application/json")
            .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.draftType").value("QUOTE"))
        .andExpect(jsonPath("$.draftId").value(draftId.toString()))
        .andExpect(jsonPath("$.sourceHandoffId").value(reviewCaseId.toString()))
        .andExpect(jsonPath("$.created").value(true))
        .andExpect(jsonPath("$.alreadyExisted").value(false))
        .andExpect(jsonPath("$.externalExecution").value("DISABLED"))
        .andExpect(jsonPath("$.nextAction").value("OPEN_OPERATOR_WORKSPACE"));
  }

  @Test
  void prepareDraftMapsUnsupportedIntentToStructuredBadRequest() throws Exception {
    UUID reviewCaseId = UUID.randomUUID();
    when(draftCommandPreparationService.prepareDraft(eq(reviewCaseId), any()))
        .thenThrow(new IllegalArgumentException("Unsupported document intent for draft preparation: AVAILABILITY_REQUEST"));

    mockMvc.perform(post("/api/v1/validation-review/" + reviewCaseId + "/prepare-draft")
            .contentType("application/json")
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
  }
}
