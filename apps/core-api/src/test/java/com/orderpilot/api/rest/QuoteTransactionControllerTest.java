package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.Stage12ADtos.*;
import com.orderpilot.application.services.workspace.ChannelToQuoteWiringService;
import com.orderpilot.application.services.workspace.QuoteApprovalStateMachineService;
import com.orderpilot.application.services.workspace.QuoteDraftService;
import com.orderpilot.application.services.workspace.QuoteLifecycleViolation;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.idempotency.IdempotencyService;
import com.orderpilot.common.tenant.TenantContextFilter;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.RequestActorResolver;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(QuoteTransactionController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, NoopApiPermissionTestConfig.class, TenantContextFilter.class})
class QuoteTransactionControllerTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @MockBean private QuoteDraftService quoteDraftService;
  @MockBean private QuoteApprovalStateMachineService approvalStateMachineService;
  @MockBean private ChannelToQuoteWiringService channelToQuoteWiringService;
  @MockBean private IdempotencyService idempotencyService;
  @MockBean private RequestActorResolver actorResolver;

  @BeforeEach
  void setUpIdempotencyWrapper() {
    lenient().when(actorResolver.resolveVerifiedActor(any(), any())).thenReturn(null);
    lenient().when(idempotencyService.execute(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(8)).get());
  }

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
            .header("X-Tenant-Id", tenantId.toString())
            .header("Idempotency-Key", "api-test-1")
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.draftQuoteId").value(quoteId.toString()))
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.approvalRequired").value(false));
  }

  @Test
  void exposesApprovalStateEndpoint() throws Exception {
    UUID quoteId = UUID.randomUUID();
    when(approvalStateMachineService.getQuoteApprovalState(quoteId)).thenReturn(new QuoteApprovalStateResponse(
        quoteId,
        "PENDING_APPROVAL",
        true,
        List.of(),
        List.of("DISCOUNT_APPROVAL_REQUIRED"),
        List.of(),
        null,
        null,
        null,
        false));

    mockMvc.perform(get("/api/v1/quotes/" + quoteId + "/approval-state").header("X-Tenant-Id", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.quoteId").value(quoteId.toString()))
        .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
        .andExpect(jsonPath("$.approvalRequired").value(true))
        .andExpect(jsonPath("$.externalExecutionEnabled").value(false))
        // Category D: internal audit/actor/execution internals must not leak on the response.
        .andExpect(jsonPath("$.auditCorrelationId").doesNotExist())
        .andExpect(jsonPath("$.externalExecutionStatus").doesNotExist())
        .andExpect(jsonPath("$.approvalDecision.decidedBy").doesNotExist())
        .andExpect(jsonPath("$.approvalDecision.auditCorrelationId").doesNotExist());
  }

  @Test
  void approveRejectRequestChangesAndConvertEndpointsCallService() throws Exception {
    UUID quoteId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    QuoteApprovalCommandResponse approved = response(quoteId, "PENDING_APPROVAL", "APPROVED", "APPROVE");
    QuoteApprovalCommandResponse rejected = response(quoteId, "PENDING_APPROVAL", "REJECTED", "REJECT");
    QuoteApprovalCommandResponse changes = response(quoteId, "PENDING_APPROVAL", "CHANGES_REQUESTED", "REQUEST_CHANGES");
    QuoteApprovalCommandResponse converted = new QuoteApprovalCommandResponse(quoteId, "APPROVED", "CONVERTED_TO_INTERNAL_ORDER", false, "CONVERT", List.of(), List.of(), UUID.randomUUID(), null, false);
    when(approvalStateMachineService.approveQuote(eq(quoteId), any())).thenReturn(approved);
    when(approvalStateMachineService.rejectQuote(eq(quoteId), any())).thenReturn(rejected);
    when(approvalStateMachineService.requestQuoteChanges(eq(quoteId), any())).thenReturn(changes);
    when(approvalStateMachineService.convertApprovedQuoteToInternalDraftOrder(eq(quoteId), any())).thenReturn(converted);
    QuoteApprovalDecisionCommand command = new QuoteApprovalDecisionCommand(tenantId, UUID.randomUUID(), "OPERATOR", null, "decision note", "decision note", "idem");

    mockMvc.perform(post("/api/v1/quotes/" + quoteId + "/approve").contentType("application/json").header("X-Tenant-Id", tenantId.toString()).header("Idempotency-Key", "approve-key").content(objectMapper.writeValueAsString(command)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.newStatus").value("APPROVED"));
    mockMvc.perform(post("/api/v1/quotes/" + quoteId + "/reject").contentType("application/json").header("X-Tenant-Id", tenantId.toString()).header("Idempotency-Key", "reject-key").content(objectMapper.writeValueAsString(command)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.newStatus").value("REJECTED"));
    mockMvc.perform(post("/api/v1/quotes/" + quoteId + "/request-changes").contentType("application/json").header("X-Tenant-Id", tenantId.toString()).header("Idempotency-Key", "changes-key").content(objectMapper.writeValueAsString(command)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.newStatus").value("CHANGES_REQUESTED"));
    mockMvc.perform(post("/api/v1/quotes/" + quoteId + "/convert-to-internal-order").contentType("application/json").header("X-Tenant-Id", tenantId.toString()).header("Idempotency-Key", "convert-key").content(objectMapper.writeValueAsString(command)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.newStatus").value("CONVERTED_TO_INTERNAL_ORDER")).andExpect(jsonPath("$.externalExecutionEnabled").value(false)).andExpect(jsonPath("$.externalExecutionStatus").doesNotExist()).andExpect(jsonPath("$.auditCorrelationId").doesNotExist());
  }

  @Test
  void structuredErrorReturnedForInvalidTransition() throws Exception {
    UUID quoteId = UUID.randomUUID();
    when(approvalStateMachineService.approveQuote(eq(quoteId), any())).thenThrow(new QuoteLifecycleViolation("Quote status CONVERTED_TO_INTERNAL_ORDER does not allow another approval decision"));

    mockMvc.perform(post("/api/v1/quotes/" + quoteId + "/approve")
            .contentType("application/json")
            .header("X-Tenant-Id", UUID.randomUUID().toString())
            .header("Idempotency-Key", "invalid-transition-key")
            .content("{}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("QUOTE_LIFECYCLE_TRANSITION_BLOCKED"));
  }

  @Test
  void tenantHeaderBehaviorReportsMissingTenant() throws Exception {
    UUID quoteId = UUID.randomUUID();
    when(approvalStateMachineService.getQuoteApprovalState(quoteId)).thenThrow(new com.orderpilot.common.tenant.TenantContextMissingException("Missing tenant header X-Tenant-Id"));

    mockMvc.perform(get("/api/v1/quotes/" + quoteId + "/approval-state"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
  }

  private QuoteApprovalCommandResponse response(UUID quoteId, String previous, String next, String decision) {
    return new QuoteApprovalCommandResponse(quoteId, previous, next, false, decision, List.of(), List.of(), null, null, false);
  }
}
