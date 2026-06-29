package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationApprovalRequestCommand;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationIssueResolutionRequest;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewActionResult;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewCorrectionRequest;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftResult;
import com.orderpilot.application.services.validation.AdvisoryExtractionValidationHandoffService;
import com.orderpilot.application.services.validation.ApprovalRequirementService;
import com.orderpilot.application.services.validation.CustomerMatchingService;
import com.orderpilot.application.services.validation.DiscountValidationService;
import com.orderpilot.application.services.validation.ExtractionValidationService;
import com.orderpilot.application.services.validation.InventoryValidationService;
import com.orderpilot.application.services.validation.MarginValidationService;
import com.orderpilot.application.services.validation.PricingValidationService;
import com.orderpilot.application.services.validation.ProductMatchingService;
import com.orderpilot.application.services.validation.SubstitutionEngineService;
import com.orderpilot.application.services.validation.UomNormalizationService;
import com.orderpilot.application.services.validation.ValidationIssueService;
import com.orderpilot.application.services.validation.ValidationReviewCommandService;
import com.orderpilot.application.services.validation.ValidationReviewDraftCommandService;
import com.orderpilot.application.services.validation.ValidationReviewDraftQueryService;
import com.orderpilot.application.services.validation.ValidationReviewDraftRemediationLineageService;
import com.orderpilot.application.services.validation.ValidationReviewDraftabilityService;
import com.orderpilot.application.services.validation.ValidationReviewQueryService;
import com.orderpilot.application.services.validation.ValidationRunService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.tenant.TenantContextFilter;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.ApiPermissionGuard;
import com.orderpilot.security.ApiPermissionInterceptor;
import com.orderpilot.security.ApiSecurityWebConfig;
import com.orderpilot.security.RequestActorResolver;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ValidationController.class)
@Import({
    CoreConfiguration.class,
    GlobalExceptionHandler.class,
    ApiSecurityWebConfig.class,
    ApiPermissionInterceptor.class,
    ApiPermissionGuard.class,
    RequestActorResolver.class,
    TenantContextFilter.class
})
class ValidationControllerAuthorityBoundaryTest {
  private static final String TENANT_HEADER = "X-Tenant-Id";
  private static final String ACTOR_HEADER = RequestActorResolver.ACTOR_HEADER;
  private static final String PERMISSIONS_HEADER = ApiPermissionGuard.PERMISSIONS_HEADER;

  @Autowired private MockMvc mockMvc;

  @MockBean private ValidationRunService runService;
  @MockBean private ValidationIssueService issueService;
  @MockBean private CustomerMatchingService customerMatchingService;
  @MockBean private ProductMatchingService productMatchingService;
  @MockBean private UomNormalizationService uomNormalizationService;
  @MockBean private InventoryValidationService inventoryValidationService;
  @MockBean private PricingValidationService pricingValidationService;
  @MockBean private DiscountValidationService discountValidationService;
  @MockBean private MarginValidationService marginValidationService;
  @MockBean private SubstitutionEngineService substitutionEngineService;
  @MockBean private ApprovalRequirementService approvalRequirementService;
  @MockBean private ExtractionValidationService extractionValidationService;
  @MockBean private AdvisoryExtractionValidationHandoffService advisoryValidationHandoffService;
  @MockBean private ValidationReviewQueryService validationReviewQueryService;
  @MockBean private ValidationReviewCommandService validationReviewCommandService;
  @MockBean private ValidationReviewDraftCommandService validationReviewDraftCommandService;
  @MockBean private ValidationReviewDraftabilityService validationReviewDraftabilityService;
  @MockBean private ValidationReviewDraftQueryService validationReviewDraftQueryService;
  @MockBean private ValidationReviewDraftRemediationLineageService validationReviewDraftRemediationLineageService;

  @Test
  void createDraftQuoteUsesTrustedActorAndIgnoresClientAuthorityFields() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID validationRunId = UUID.randomUUID();
    UUID draftId = UUID.randomUUID();
    UUID trustedActor = UUID.randomUUID();
    UUID spoofActor = UUID.randomUUID();
    UUID selectedLine = UUID.randomUUID();
    when(validationReviewDraftCommandService.createDraftQuote(eq(validationRunId), any(), any(), any()))
        .thenReturn(new ValidationReviewDraftResult(
            draftId, "QUOTE", "DRAFT", validationRunId, 1, 0, 0, false,
            true, false, "DISABLED", "OPEN_OPERATOR_WORKSPACE", "/workspace/draft-quotes/" + draftId));

    mockMvc.perform(post("/api/v1/validations/{validationRunId}/review/draft-quote", validationRunId)
            .header(TENANT_HEADER, tenant.toString())
            .header(ACTOR_HEADER, trustedActor.toString())
            .header(PERMISSIONS_HEADER, "REVIEW_ACTION")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "actorUserId": "%s",
                  "selectedLineIds": ["%s"],
                  "operatorNote": "operator selected one line",
                  "status": "APPROVED",
                  "approvalState": "APPROVED",
                  "executionState": "EXECUTED",
                  "marginPercent": 99,
                  "stockStatus": "AVAILABLE"
                }
                """.formatted(spoofActor, selectedLine)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.draftType").value("QUOTE"))
        .andExpect(jsonPath("$.externalExecution").value("DISABLED"))
        .andExpect(content().string(not(containsString(spoofActor.toString()))))
        .andExpect(content().string(not(containsString("executionState"))))
        .andExpect(content().string(not(containsString("marginPercent"))))
        .andExpect(content().string(not(containsString("stockStatus"))));

    ArgumentCaptor<UUID> actorCaptor = ArgumentCaptor.forClass(UUID.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<UUID>> linesCaptor = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<String> noteCaptor = ArgumentCaptor.forClass(String.class);
    verify(validationReviewDraftCommandService).createDraftQuote(
        eq(validationRunId), actorCaptor.capture(), linesCaptor.capture(), noteCaptor.capture());
    assertThat(actorCaptor.getValue()).isEqualTo(trustedActor).isNotEqualTo(spoofActor);
    assertThat(linesCaptor.getValue()).containsExactly(selectedLine);
    assertThat(noteCaptor.getValue()).isEqualTo("operator selected one line");
  }

  @Test
  void correctionUsesTrustedActorAndPreservesBusinessInputOnly() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID validationRunId = UUID.randomUUID();
    UUID lineItemId = UUID.randomUUID();
    UUID actionId = UUID.randomUUID();
    UUID trustedActor = UUID.randomUUID();
    UUID spoofActor = UUID.randomUUID();
    when(validationReviewCommandService.submitCorrection(eq(validationRunId), any(), any()))
        .thenReturn(new ValidationReviewActionResult(
            actionId, validationRunId, "LINE_ITEM", lineItemId, "VALIDATION_REVIEW_LINE_ITEM_CORRECTED",
            "RECORDED", false, null, null, null, trustedActor, Instant.parse("2026-06-16T00:00:00Z"),
            "client-1", "Line item correction recorded for operator review."));

    mockMvc.perform(post("/api/v1/validations/{validationRunId}/review/corrections", validationRunId)
            .header(TENANT_HEADER, tenant.toString())
            .header(ACTOR_HEADER, trustedActor.toString())
            .header(PERMISSIONS_HEADER, "REVIEW_ACTION")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "targetType": "LINE_ITEM",
                  "targetId": "%s",
                  "correctedQuantity": "3",
                  "correctedUom": "EA",
                  "reason": "operator corrected quantity",
                  "actorUserId": "%s",
                  "actorId": "%s",
                  "reviewedBy": "%s",
                  "decidedBy": "%s",
                  "clientRequestId": "client-1",
                  "tenantId": "00000000-0000-4000-8000-000000000001",
                  "risk": "LOW",
                  "approvalStatus": "APPROVED"
                }
                """.formatted(lineItemId, spoofActor, spoofActor, spoofActor, spoofActor)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.createdBy").value(trustedActor.toString()))
        .andExpect(content().string(not(containsString(spoofActor.toString()))))
        .andExpect(content().string(not(containsString("approvalStatus"))))
        .andExpect(content().string(not(containsString("actorUserId"))))
        .andExpect(content().string(not(containsString("tenantId"))))
        .andExpect(content().string(not(containsString("auditCorrelationId"))))
        .andExpect(content().string(not(containsString("idempotencyKey"))))
        .andExpect(content().string(not(containsString("rawPayload"))));

    ArgumentCaptor<ValidationReviewCorrectionRequest> requestCaptor =
        ArgumentCaptor.forClass(ValidationReviewCorrectionRequest.class);
    ArgumentCaptor<UUID> actorCaptor = ArgumentCaptor.forClass(UUID.class);
    verify(validationReviewCommandService).submitCorrection(
        eq(validationRunId), requestCaptor.capture(), actorCaptor.capture());
    ValidationReviewCorrectionRequest request = requestCaptor.getValue();
    assertThat(actorCaptor.getValue()).isEqualTo(trustedActor).isNotEqualTo(spoofActor);
    assertThat(request.correctedQuantity()).isEqualTo("3");
    assertThat(request.correctedUom()).isEqualTo("EA");
    assertThat(request.reason()).isEqualTo("operator corrected quantity");
  }

  @Test
  void issueResolutionUsesTrustedActorAndIgnoresClientAuthorityFields() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID validationRunId = UUID.randomUUID();
    UUID issueId = UUID.randomUUID();
    UUID actionId = UUID.randomUUID();
    UUID trustedActor = UUID.randomUUID();
    UUID spoofActor = UUID.randomUUID();
    when(validationReviewCommandService.resolveIssue(
            eq(validationRunId), eq(issueId), any(), any()))
        .thenReturn(new ValidationReviewActionResult(
            actionId, validationRunId, "VALIDATION_ISSUE", issueId,
            "VALIDATION_REVIEW_ISSUE_RESOLVED", "RESOLVED", false, null, issueId,
            "RESOLVED", trustedActor, Instant.parse("2026-06-16T00:00:00Z"), "client-2",
            "Issue marked RESOLVED."));

    mockMvc.perform(post(
                "/api/v1/validations/{validationRunId}/review/issues/{issueId}/resolution",
                validationRunId, issueId)
            .header(TENANT_HEADER, tenant.toString())
            .header(ACTOR_HEADER, trustedActor.toString())
            .header(PERMISSIONS_HEADER, "REVIEW_ACTION")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "resolution": "RESOLVED",
                  "reason": "operator verified correction",
                  "clientRequestId": "client-2",
                  "actorUserId": "%s",
                  "actorId": "%s",
                  "reviewedBy": "%s",
                  "decidedBy": "%s"
                }
                """.formatted(spoofActor, spoofActor, spoofActor, spoofActor)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.createdBy").value(trustedActor.toString()))
        .andExpect(content().string(not(containsString(spoofActor.toString()))));

    ArgumentCaptor<ValidationIssueResolutionRequest> requestCaptor =
        ArgumentCaptor.forClass(ValidationIssueResolutionRequest.class);
    ArgumentCaptor<UUID> actorCaptor = ArgumentCaptor.forClass(UUID.class);
    verify(validationReviewCommandService).resolveIssue(
        eq(validationRunId), eq(issueId), requestCaptor.capture(), actorCaptor.capture());
    assertThat(requestCaptor.getValue().resolution()).isEqualTo("RESOLVED");
    assertThat(requestCaptor.getValue().reason()).isEqualTo("operator verified correction");
    assertThat(actorCaptor.getValue()).isEqualTo(trustedActor).isNotEqualTo(spoofActor);
  }

  @Test
  void approvalRequestUsesTrustedActorAndIgnoresClientAuthorityFields() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID validationRunId = UUID.randomUUID();
    UUID approvalId = UUID.randomUUID();
    UUID actionId = UUID.randomUUID();
    UUID trustedActor = UUID.randomUUID();
    UUID spoofActor = UUID.randomUUID();
    when(validationReviewCommandService.requestApproval(eq(validationRunId), any(), any()))
        .thenReturn(new ValidationReviewActionResult(
            actionId, validationRunId, "APPROVAL_REQUIREMENT", approvalId,
            "VALIDATION_REVIEW_APPROVAL_REQUESTED", "OPEN", true, approvalId, null, null,
            trustedActor, Instant.parse("2026-06-16T00:00:00Z"), null,
            "Approval request created and pending review."));

    mockMvc.perform(post(
                "/api/v1/validations/{validationRunId}/review/approval-requests",
                validationRunId)
            .header(TENANT_HEADER, tenant.toString())
            .header(ACTOR_HEADER, trustedActor.toString())
            .header(PERMISSIONS_HEADER, "REVIEW_ACTION")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "requirementType": "OPERATOR_CORRECTION_REVIEW",
                  "reason": "manager sign-off required",
                  "actorUserId": "%s",
                  "actorId": "%s",
                  "reviewedBy": "%s",
                  "decidedBy": "%s"
                }
                """.formatted(spoofActor, spoofActor, spoofActor, spoofActor)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.createdBy").value(trustedActor.toString()))
        .andExpect(content().string(not(containsString(spoofActor.toString()))));

    ArgumentCaptor<ValidationApprovalRequestCommand> requestCaptor =
        ArgumentCaptor.forClass(ValidationApprovalRequestCommand.class);
    ArgumentCaptor<UUID> actorCaptor = ArgumentCaptor.forClass(UUID.class);
    verify(validationReviewCommandService).requestApproval(
        eq(validationRunId), requestCaptor.capture(), actorCaptor.capture());
    assertThat(requestCaptor.getValue().requirementType())
        .isEqualTo("OPERATOR_CORRECTION_REVIEW");
    assertThat(requestCaptor.getValue().reason()).isEqualTo("manager sign-off required");
    assertThat(actorCaptor.getValue()).isEqualTo(trustedActor).isNotEqualTo(spoofActor);
  }

  @Test
  void correctionWithoutReviewActionIsDeniedBeforeServiceInvocation() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID validationRunId = UUID.randomUUID();
    UUID lineItemId = UUID.randomUUID();

    mockMvc.perform(post("/api/v1/validations/{validationRunId}/review/corrections", validationRunId)
            .header(TENANT_HEADER, tenant.toString())
            .header(PERMISSIONS_HEADER, "VALIDATION_READ")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "targetType": "LINE_ITEM",
                  "targetId": "%s",
                  "correctedQuantity": "3",
                  "reason": "operator corrected quantity"
                }
                """.formatted(lineItemId)))
        .andExpect(status().isForbidden());

    verify(validationReviewCommandService, never()).submitCorrection(any(), any(), any());
  }

  @Test
  void businessInvalidDraftSelectionReturnsBusinessBadRequest() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID validationRunId = UUID.randomUUID();
    UUID trustedActor = UUID.randomUUID();
    when(validationReviewDraftCommandService.createDraftQuote(eq(validationRunId), any(), any(), any()))
        .thenThrow(new IllegalArgumentException("selected_lines_empty"));

    mockMvc.perform(post("/api/v1/validations/{validationRunId}/review/draft-quote", validationRunId)
            .header(TENANT_HEADER, tenant.toString())
            .header(ACTOR_HEADER, trustedActor.toString())
            .header(PERMISSIONS_HEADER, "REVIEW_ACTION")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"selectedLineIds\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.message").value("selected_lines_empty"));
  }
}
