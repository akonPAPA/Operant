package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.Stage12ADtos.CreateDraftQuoteFromRfqCommand;
import com.orderpilot.api.dto.Stage12ADtos.QuoteApprovalCommandResponse;
import com.orderpilot.api.dto.Stage12ADtos.QuoteApprovalDecisionCommand;
import com.orderpilot.api.dto.Stage12ADtos.QuoteTransactionResponse;
import com.orderpilot.application.services.workspace.ChannelToQuoteWiringService;
import com.orderpilot.application.services.workspace.QuoteApprovalStateMachineService;
import com.orderpilot.application.services.workspace.QuoteDraftService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.idempotency.IdempotencyService;
import com.orderpilot.common.tenant.TenantContextFilter;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.RequestActorResolver;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

// OP-CAP-31: a direct API caller (Postman/curl/CLI) cannot control tenant or actor authority via the
// request body. tenant comes from TenantContext (X-Tenant-Id), actor from RequestActorResolver.
@WebMvcTest(QuoteTransactionController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, NoopApiPermissionTestConfig.class, TenantContextFilter.class})
class QuoteTransactionAuthorityContractTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private QuoteDraftService quoteDraftService;
  @MockBean private QuoteApprovalStateMachineService approvalStateMachineService;
  @MockBean private ChannelToQuoteWiringService channelToQuoteWiringService;
  @MockBean private IdempotencyService idempotencyService;
  @MockBean private RequestActorResolver actorResolver;

  private final UUID trustedActor = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    when(actorResolver.resolveVerifiedActor(any(), any())).thenReturn(trustedActor);
    lenient().when(idempotencyService.execute(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(8)).get());
  }

  @Test
  void rfqBodyCannotControlTenantOrActorAndValidFlowSucceeds() throws Exception {
    UUID headerTenant = UUID.randomUUID();
    UUID spoofTenant = UUID.randomUUID();
    UUID spoofActor = UUID.randomUUID();
    when(quoteDraftService.createFromRfq(any())).thenReturn(rfqResponse());

    mockMvc.perform(post("/api/v1/quotes/from-rfq")
            .contentType("application/json")
            .header("X-Tenant-Id", headerTenant.toString())
            .header("Idempotency-Key", "rfq-authority-1")
            .content("{\"tenantId\":\"" + spoofTenant + "\",\"actorId\":\"" + spoofActor + "\",\"actorRole\":\"ADMIN\",\"customerExternalRef\":\"CUST-1\",\"requestedItems\":[{\"rawSkuOrAlias\":\"BRK-001\",\"description\":\"Brake pads\",\"quantity\":1,\"uom\":\"EA\"}]}"))
        .andExpect(status().isOk());

    ArgumentCaptor<CreateDraftQuoteFromRfqCommand> captor = ArgumentCaptor.forClass(CreateDraftQuoteFromRfqCommand.class);
    verify(quoteDraftService).createFromRfq(captor.capture());
    CreateDraftQuoteFromRfqCommand command = captor.getValue();
    assertThat(command.tenantId()).isEqualTo(headerTenant).isNotEqualTo(spoofTenant);
    assertThat(command.actorId()).isEqualTo(trustedActor).isNotEqualTo(spoofActor);
    assertThat(command.actorRole()).isNull();
    assertThat(command.customerExternalRef()).isEqualTo("CUST-1");
  }

  @Test
  void approveBodyCannotControlTenantOrActor() throws Exception {
    UUID quoteId = UUID.randomUUID();
    UUID headerTenant = UUID.randomUUID();
    UUID spoofActor = UUID.randomUUID();
    when(approvalStateMachineService.approveQuote(eq(quoteId), any())).thenReturn(approvalResponse(quoteId));

    mockMvc.perform(post("/api/v1/quotes/" + quoteId + "/approve")
            .contentType("application/json")
            .header("X-Tenant-Id", headerTenant.toString())
            .header("Idempotency-Key", "approve-authority-1")
            .content("{\"tenantId\":\"" + UUID.randomUUID() + "\",\"actorId\":\"" + spoofActor + "\",\"actorRole\":\"ADMIN\",\"reason\":\"ok\"}"))
        .andExpect(status().isOk());

    ArgumentCaptor<QuoteApprovalDecisionCommand> captor = ArgumentCaptor.forClass(QuoteApprovalDecisionCommand.class);
    verify(approvalStateMachineService).approveQuote(eq(quoteId), captor.capture());
    QuoteApprovalDecisionCommand command = captor.getValue();
    assertThat(command.tenantId()).isEqualTo(headerTenant);
    assertThat(command.actorId()).isEqualTo(trustedActor).isNotEqualTo(spoofActor);
    assertThat(command.actorRole()).isNull();
    assertThat(command.reason()).isEqualTo("ok");
  }

  private QuoteTransactionResponse rfqResponse() {
    return new QuoteTransactionResponse(UUID.randomUUID(), "DRAFT", null, List.of(), List.of(), List.of(), false, List.of(), UUID.randomUUID(), List.of());
  }

  private QuoteApprovalCommandResponse approvalResponse(UUID quoteId) {
    return new QuoteApprovalCommandResponse(quoteId, "PENDING_APPROVAL", "APPROVED", false, "APPROVE", List.of(), List.of(), null, null, "EXTERNAL_EXECUTION_DISABLED", UUID.randomUUID());
  }
}
