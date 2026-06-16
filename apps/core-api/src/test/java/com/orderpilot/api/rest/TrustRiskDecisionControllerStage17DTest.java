package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.TrustDtos.TrustApprovalRequirementView;
import com.orderpilot.api.dto.TrustDtos.TrustRiskDecisionView;
import com.orderpilot.api.dto.TrustDtos.TrustRiskEvaluationResponse;
import com.orderpilot.api.dto.TrustDtos.TrustRiskSignalContributionView;
import com.orderpilot.application.services.trust.TrustRiskDecisionService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.tenant.TenantContextFilter;
import com.orderpilot.domain.trust.TrustRiskDecision;
import com.orderpilot.domain.trust.TrustRiskLevel;
import com.orderpilot.infrastructure.config.CoreConfiguration;
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
 * OP-CAP-17D Trust Risk Decision Engine — read/evaluate/override endpoint contract and tenant scoping.
 */
@WebMvcTest(TrustRiskDecisionController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, NoopApiPermissionTestConfig.class,
    TenantContextFilter.class})
class TrustRiskDecisionControllerStage17DTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private TrustRiskDecisionService service;

  private static final String TENANT = UUID.randomUUID().toString();

  private TrustRiskDecisionView view(UUID id) {
    return new TrustRiskDecisionView(
        id, "DOCUMENT", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null,
        "HIGH", 60, "REQUIRE_APPROVAL", true, true, 1, "HIGH risk: DOCUMENT_HIGH_RISK_SIGNAL", "ACTIVE",
        Instant.parse("2026-06-14T00:00:00Z"), Instant.parse("2026-06-14T00:00:00Z"),
        List.of(new TrustRiskSignalContributionView(UUID.randomUUID(), "DOCUMENT_TRUST", UUID.randomUUID(),
            "DOCUMENT_HIGH_RISK_SIGNAL", "HIGH", null, 1, 50, "HIGH", "Document trust run HIGH.", "ref",
            Instant.parse("2026-06-14T00:00:00Z"))),
        List.of(new TrustApprovalRequirementView(UUID.randomUUID(), "REQUIRE_APPROVAL", "REVIEW_ACTION",
            null, "DOCUMENT_HIGH_RISK_SIGNAL", "PENDING", Instant.parse("2026-06-14T00:00:00Z"), null)),
        List.of());
  }

  @Test
  void getDecisionReturnsContributionsAndApprovalRequirements() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.getDecisionView(eq(id))).thenReturn(view(id));

    mockMvc.perform(get("/api/v1/trust/risk-decisions/" + id).header("X-Tenant-Id", TENANT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.riskLevel").value("HIGH"))
        .andExpect(jsonPath("$.action").value("REQUIRE_APPROVAL"))
        .andExpect(jsonPath("$.blocking").value(true))
        .andExpect(jsonPath("$.contributions[0].signalCode").value("DOCUMENT_HIGH_RISK_SIGNAL"))
        .andExpect(jsonPath("$.approvalRequirements[0].status").value("PENDING"));
  }

  @Test
  void listEndpointForwardsTenantScopedFiltersAndPaging() throws Exception {
    when(service.listDecisions(any(), any(), any(), any(), eq(2), eq(50))).thenReturn(List.of());

    mockMvc.perform(get("/api/v1/trust/risk-decisions?riskLevel=HIGH&status=ACTIVE&page=2&size=50")
            .header("X-Tenant-Id", TENANT))
        .andExpect(status().isOk());

    verify(service).listDecisions(null, null, "HIGH", "ACTIVE", 2, 50);
  }

  @Test
  void evaluateReturnsCompactResponse() throws Exception {
    TrustRiskEvaluationResponse response = new TrustRiskEvaluationResponse(
        UUID.randomUUID(), "DOCUMENT", UUID.randomUUID(), "HIGH", 60, "REQUIRE_APPROVAL", true, true,
        "HIGH risk: DOCUMENT_HIGH_RISK_SIGNAL", List.of("DOCUMENT_HIGH_RISK_SIGNAL"), List.of());
    when(service.evaluate(any())).thenReturn((TrustRiskDecision) null);
    when(service.toEvaluationResponse(any(), any())).thenReturn(response);

    mockMvc.perform(post("/api/v1/trust/risk-decisions/evaluate")
            .header("X-Tenant-Id", TENANT)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"subjectType\":\"DOCUMENT\",\"subjectId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.riskLevel").value("HIGH"))
        .andExpect(jsonPath("$.action").value("REQUIRE_APPROVAL"))
        .andExpect(jsonPath("$.reasonCodes[0]").value("DOCUMENT_HIGH_RISK_SIGNAL"));
  }

  @Test
  void overrideDelegatesToServiceAndReturnsUpdatedDecision() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.getDecisionView(eq(id))).thenReturn(view(id));

    mockMvc.perform(post("/api/v1/trust/risk-decisions/" + id + "/override")
            .header("X-Tenant-Id", TENANT)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"newRiskLevel\":\"MEDIUM\",\"newAction\":\"CONTINUE_WITH_WARNING\",\"reason\":\"verified\"}"))
        .andExpect(status().isOk());

    verify(service).overrideDecision(any(), eq(id), eq(TrustRiskLevel.MEDIUM), any(), eq("verified"), any());
  }
}
