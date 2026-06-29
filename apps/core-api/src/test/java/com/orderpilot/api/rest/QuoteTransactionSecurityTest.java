package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.Stage12ADtos.QuoteApprovalCommandResponse;
import com.orderpilot.api.dto.Stage12ADtos.QuoteApprovalStateResponse;
import com.orderpilot.api.dto.Stage12BDtos.ChannelToQuoteResponse;
import com.orderpilot.application.services.workspace.ChannelToQuoteWiringService;
import com.orderpilot.application.services.workspace.QuoteApprovalStateMachineService;
import com.orderpilot.application.services.workspace.QuoteDraftService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.idempotency.IdempotencyService;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.ApiPermissionGuard;
import com.orderpilot.security.ApiPermissionInterceptor;
import com.orderpilot.security.ApiSecurityWebConfig;
import com.orderpilot.security.RequestActorResolver;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({QuoteTransactionController.class, QuoteTransactionConversionController.class})
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, ApiSecurityWebConfig.class, ApiPermissionInterceptor.class, ApiPermissionGuard.class})
class QuoteTransactionSecurityTest {
  @Autowired private MockMvc mockMvc;
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
  void quoteReadRequiresQuoteReadPermission() throws Exception {
    UUID quoteId = UUID.randomUUID();
    when(approvalStateMachineService.getQuoteApprovalState(quoteId)).thenReturn(new QuoteApprovalStateResponse(quoteId, "DRAFT", false, List.of(), List.of(), List.of(), null, null, null, false));

    mockMvc.perform(get("/api/v1/quotes/" + quoteId + "/approval-state")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "AUTHENTICATED_PROBE"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Missing required API permission QUOTE_READ"));

    mockMvc.perform(get("/api/v1/quotes/" + quoteId + "/approval-state")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "QUOTE_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.quoteId").value(quoteId.toString()));
  }

  @Test
  void quoteApprovalMutationRequiresQuoteActionPermission() throws Exception {
    UUID quoteId = UUID.randomUUID();
    when(approvalStateMachineService.approveQuote(eq(quoteId), any())).thenReturn(new QuoteApprovalCommandResponse(quoteId, "PENDING_APPROVAL", "APPROVED", false, "APPROVE", List.of(), List.of(), null, null, false));

    mockMvc.perform(post("/api/v1/quotes/" + quoteId + "/approve")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "QUOTE_READ")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Missing required API permission QUOTE_ACTION"));

    mockMvc.perform(post("/api/v1/quotes/" + quoteId + "/approve")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "QUOTE_ACTION")
            .header("X-Tenant-Id", UUID.randomUUID().toString())
            .header("Idempotency-Key", "approve-security-test")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.newStatus").value("APPROVED"));
  }

  @Test
  void channelToQuoteMutationRequiresQuoteActionPermission() throws Exception {
    UUID messageId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    when(channelToQuoteWiringService.createFromChannelMessage(eq(messageId), any(), any(), any())).thenReturn(new ChannelToQuoteResponse("NEEDS_REVIEW", null, attemptId, "CHANNEL_MESSAGE", "UNRESOLVED", 0, 0, List.of(), true));

    mockMvc.perform(post("/api/v1/quote-transactions/from-channel-message/" + messageId)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "AUTHENTICATED_PROBE")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Missing required API permission QUOTE_ACTION"));

    mockMvc.perform(post("/api/v1/quote-transactions/from-channel-message/" + messageId)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "QUOTE_ACTION")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("NEEDS_REVIEW"))
        .andExpect(jsonPath("$.conversionAttemptId").doesNotExist())
        .andExpect(jsonPath("$.sourceId").doesNotExist())
        .andExpect(jsonPath("$.auditEventIds").doesNotExist());
  }
}
