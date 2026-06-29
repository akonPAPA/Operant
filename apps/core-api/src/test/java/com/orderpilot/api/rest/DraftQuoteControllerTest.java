package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.Stage11ADtos.*;
import com.orderpilot.api.dto.Stage11EDtos.*;
import com.orderpilot.application.services.workspace.RfqToDraftQuoteService;
import com.orderpilot.application.services.workspace.QuoteExternalWritePreparationService;
import com.orderpilot.application.services.workspace.SubstituteApprovalService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.tenant.TenantContextFilter;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.RequestActorResolver;
import com.orderpilot.security.RequestActorRoleResolver;
import com.orderpilot.security.policy.ActorRole;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DraftQuoteController.class)
@Import({
    CoreConfiguration.class,
    GlobalExceptionHandler.class,
    NoopApiPermissionTestConfig.class,
    TenantContextFilter.class
})
class DraftQuoteControllerTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @MockBean private RfqToDraftQuoteService service;
  @MockBean private SubstituteApprovalService substituteApprovalService;
  @MockBean private QuoteExternalWritePreparationService externalWritePreparationService;
  @MockBean private RequestActorResolver actorResolver;
  @MockBean private RequestActorRoleResolver roleResolver;

  private final UUID tenantId = UUID.randomUUID();
  private final UUID trustedActor = UUID.randomUUID();

  @BeforeEach
  void trustedAuthority() {
    when(actorResolver.resolveVerifiedActor(any(), any())).thenReturn(trustedActor);
    when(roleResolver.resolveQuoteRole()).thenReturn(ActorRole.SALES_QUOTE_MANAGER);
  }

  @Test
  void createsDraftQuoteFromRfqEndpoint() throws Exception {
    UUID quoteId = UUID.randomUUID();
    when(service.createFromRfq(any())).thenReturn(new DraftQuoteResponse(quoteId, "DQ-1", "API", "Acme", "NEEDS_REVIEW", "NEEDS_REVIEW", true, "USD", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Instant.parse("2026-05-20T00:00:00Z"), List.of(), List.of()));

    mockMvc.perform(post("/api/v1/quotes/drafts/from-rfq")
            .header("X-Tenant-Id", tenantId)
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(new LegacyDraftQuoteCreateRequest(
                "API",
                null,
                null,
                "Acme",
                null,
                List.of(new RfqLineInput("Brake pads", "BRK-001", BigDecimal.ONE, "pcs", null))))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(quoteId.toString()))
        .andExpect(jsonPath("$.status").value("NEEDS_REVIEW"))
        // Wave 01H Category D: operator-safe response — internal tenant/source/storage ids absent.
        .andExpect(jsonPath("$.tenantId").doesNotExist())
        .andExpect(jsonPath("$.sourceMessageId").doesNotExist())
        .andExpect(jsonPath("$.sourceDocumentId").doesNotExist())
        .andExpect(jsonPath("$.customerAccountId").doesNotExist())
        .andExpect(jsonPath("$.sourceType").value("API"));

    ArgumentCaptor<CreateDraftQuoteFromRfqRequest> command =
        ArgumentCaptor.forClass(CreateDraftQuoteFromRfqRequest.class);
    verify(service).createFromRfq(command.capture());
    org.assertj.core.api.Assertions.assertThat(command.getValue().actorId()).isEqualTo(trustedActor);
    org.assertj.core.api.Assertions.assertThat(command.getValue().actorRole())
        .isEqualTo(ActorRole.SALES_QUOTE_MANAGER.name());
  }

  @Test
  void readsTenantScopedDraftQuoteEndpoint() throws Exception {
    UUID quoteId = UUID.randomUUID();
    when(service.get(quoteId)).thenReturn(new DraftQuoteResponse(quoteId, "DQ-2", "API", null, "READY_FOR_APPROVAL", "VALIDATED", true, "USD", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, Instant.parse("2026-05-20T00:00:00Z"), List.of(), List.of()));

    mockMvc.perform(get("/api/v1/quotes/drafts/" + quoteId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("READY_FOR_APPROVAL"))
        // Wave 01H Category D: operator-safe response — internal tenant/source/storage ids absent.
        .andExpect(jsonPath("$.tenantId").doesNotExist())
        .andExpect(jsonPath("$.sourceMessageId").doesNotExist())
        .andExpect(jsonPath("$.sourceDocumentId").doesNotExist())
        .andExpect(jsonPath("$.customerAccountId").doesNotExist());
  }

  @Test
  void exposesNarrowHandoffReadinessEndpoint() throws Exception {
    UUID quoteId = UUID.randomUUID();
    when(externalWritePreparationService.checkReadiness(any(), any())).thenReturn(new QuoteHandoffResponse(quoteId, "APPROVED", "READY_FOR_HANDOFF", List.of(), false, null, false, List.of("PREPARE_HANDOFF")));

    mockMvc.perform(post("/api/v1/quotes/drafts/" + quoteId + "/handoff/readiness")
            .header("X-Tenant-Id", tenantId)
            .contentType("application/json")
            .content(objectMapper.writeValueAsString(new LegacyQuoteHandoffRequest("check"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.quoteId").value(quoteId.toString()))
        .andExpect(jsonPath("$.handoffReadinessStatus").value("READY_FOR_HANDOFF"))
        .andExpect(jsonPath("$.externalExecutionEnabled").value(false))
        // Category D: internal integrity/dedupe/source internals must not leak on the response.
        .andExpect(jsonPath("$.payloadHash").doesNotExist())
        .andExpect(jsonPath("$.idempotencyKey").doesNotExist())
        .andExpect(jsonPath("$.snapshotId").doesNotExist())
        .andExpect(jsonPath("$.executionStatus").doesNotExist())
        .andExpect(jsonPath("$.payloadJson").doesNotExist());
  }

  @Test
  void forgedBodyActorAndOwnerRoleNeverReachDraftService() throws Exception {
    UUID quoteId = UUID.randomUUID();
    UUID forgedActor = UUID.randomUUID();
    when(substituteApprovalService.approveSubstitute(any(), any(), any())).thenReturn(
        new DraftQuoteResponse(
            quoteId,
            "DQ-3",
            "API",
            "Acme",
            "READY_FOR_APPROVAL",
            "VALIDATED",
            true,
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            Instant.parse("2026-05-20T00:00:00Z"),
            List.of(),
            List.of()));

    UUID lineId = UUID.randomUUID();
    UUID substituteId = UUID.randomUUID();
    mockMvc.perform(post("/api/v1/quotes/drafts/{id}/lines/{lineId}/substitute/approve", quoteId, lineId)
            .header("X-Tenant-Id", tenantId)
            .contentType("application/json")
            .content("""
                {
                  "substituteProductId":"%s",
                  "note":"business intent",
                  "actorId":"%s",
                  "actorRole":"OWNER_ADMIN"
                }
                """.formatted(substituteId, forgedActor)))
        .andExpect(status().isOk());

    ArgumentCaptor<SubstituteDecisionCommand> command =
        ArgumentCaptor.forClass(SubstituteDecisionCommand.class);
    verify(substituteApprovalService).approveSubstitute(
        org.mockito.ArgumentMatchers.eq(quoteId),
        org.mockito.ArgumentMatchers.eq(lineId),
        command.capture());
    org.assertj.core.api.Assertions.assertThat(command.getValue().actorId())
        .isEqualTo(trustedActor)
        .isNotEqualTo(forgedActor);
    org.assertj.core.api.Assertions.assertThat(command.getValue().actorRole())
        .isEqualTo(ActorRole.SALES_QUOTE_MANAGER.name());
    org.assertj.core.api.Assertions.assertThat(command.getValue().substituteProductId())
        .isEqualTo(substituteId);
  }
}
