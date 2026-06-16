package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.OperatorCorrectionLearningDtos.CorrectionLearningProjectionResponse;
import com.orderpilot.application.services.trust.OperatorCorrectionLearningService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.tenant.TenantContextFilter;
import com.orderpilot.domain.trust.ai.AiMemorySourceType;
import com.orderpilot.domain.trust.learning.OperatorCorrectionLearningRecord;
import com.orderpilot.domain.trust.learning.OperatorCorrectionStatus;
import com.orderpilot.domain.trust.learning.OperatorCorrectionType;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OP-CAP-18 Operator Correction Learning Loop — endpoint contract, delegation, and filter forwarding.
 */
@WebMvcTest(OperatorCorrectionLearningController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, NoopApiPermissionTestConfig.class,
    TenantContextFilter.class})
class OperatorCorrectionLearningControllerStage18Test {
  @Autowired private MockMvc mockMvc;
  @MockBean private OperatorCorrectionLearningService service;

  private static final String TENANT = UUID.randomUUID().toString();

  private OperatorCorrectionLearningRecord sample(OperatorCorrectionStatus statusValue) {
    OperatorCorrectionLearningRecord r = new OperatorCorrectionLearningRecord(UUID.fromString(TENANT),
        OperatorCorrectionType.PRODUCT_ALIAS, AiMemorySourceType.OPERATOR_CORRECTION, UUID.randomUUID(),
        "PRODUCT", UUID.randomUUID(), "alias", "hashprev", "hashnew", "canonical", "summary",
        new BigDecimal("0.9000"), true, null, Instant.parse("2026-06-14T12:00:00Z"));
    if (statusValue == OperatorCorrectionStatus.REJECTED) {
      r.reject("no", Instant.parse("2026-06-14T12:00:00Z"));
    }
    return r;
  }

  @Test
  void recordDelegatesAndReturnsDto() throws Exception {
    when(service.recordCorrection(any())).thenReturn(sample(OperatorCorrectionStatus.RECORDED));

    mockMvc.perform(post("/api/v1/trust/operator-corrections")
            .header("X-Tenant-Id", TENANT)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"correctionType\":\"PRODUCT_ALIAS\",\"sourceType\":\"OPERATOR_CORRECTION\","
                + "\"targetType\":\"PRODUCT\",\"correctionSummary\":\"summary\",\"confidence\":0.9}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.correctionType").value("PRODUCT_ALIAS"))
        .andExpect(jsonPath("$.status").value("RECORDED"))
        .andExpect(jsonPath("$.correctedValueHash").value("hashnew"));
  }

  @Test
  void approveDelegatesAndReturnsProjectionResponse() throws Exception {
    UUID id = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    when(service.approveCorrectionForLearning(any(), eq(id), any())).thenReturn(
        new CorrectionLearningProjectionResponse(id, "APPROVED_FOR_LEARNING", eventId, "PENDING"));

    mockMvc.perform(post("/api/v1/trust/operator-corrections/" + id + "/approve-learning")
            .header("X-Tenant-Id", TENANT)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED_FOR_LEARNING"))
        .andExpect(jsonPath("$.eventStatus").value("PENDING"));

    verify(service).approveCorrectionForLearning(any(), eq(id), any());
  }

  @Test
  void rejectDelegatesWithReason() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.rejectCorrection(any(), eq(id), eq("not reusable"), any()))
        .thenReturn(sample(OperatorCorrectionStatus.REJECTED));

    mockMvc.perform(post("/api/v1/trust/operator-corrections/" + id + "/reject-learning")
            .header("X-Tenant-Id", TENANT)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"not reusable\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));

    verify(service).rejectCorrection(any(), eq(id), eq("not reusable"), any());
  }

  @Test
  void listFiltersByStatusAndType() throws Exception {
    when(service.listCorrections(any(), eq(OperatorCorrectionStatus.RECORDED),
        eq(OperatorCorrectionType.PRODUCT_ALIAS), eq(0), eq(25)))
        .thenReturn(List.of(sample(OperatorCorrectionStatus.RECORDED)));

    mockMvc.perform(get("/api/v1/trust/operator-corrections?status=RECORDED&correctionType=PRODUCT_ALIAS")
            .header("X-Tenant-Id", TENANT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].status").value("RECORDED"));

    verify(service).listCorrections(any(), eq(OperatorCorrectionStatus.RECORDED),
        eq(OperatorCorrectionType.PRODUCT_ALIAS), eq(0), eq(25));
  }
}
