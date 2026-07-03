package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.ChannelRfqHandoffDtos.ChannelRfqHandoffResponse;
import com.orderpilot.api.dto.Stage11ADtos.DraftQuoteResponse;
import com.orderpilot.application.services.channel.RfqHandoffDraftQuoteService;
import com.orderpilot.application.services.channel.RfqHandoffDraftQuoteService.RfqHandoffDraftQuoteResult;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RfqHandoffDraftQuoteController.class)
@Import({
  CoreConfiguration.class,
  GlobalExceptionHandler.class,
  NoopApiPermissionTestConfig.class,
  TenantContextFilter.class
})
class RfqHandoffDraftQuoteControllerTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private RfqHandoffDraftQuoteService service;
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
  void createsDraftFromRouteHandleAndIgnoresBodyOwnedAuthority() throws Exception {
    UUID handoffId = UUID.randomUUID();
    UUID quoteId = UUID.randomUUID();
    when(service.createDraftQuote(
            handoffId, trustedActor, ActorRole.SALES_QUOTE_MANAGER))
        .thenReturn(result(handoffId, quoteId));

    mockMvc
        .perform(
            post("/api/v1/quotes/drafts/from-rfq-handoff/" + handoffId)
                .header("X-Tenant-Id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "tenantId":"00000000-0000-0000-0000-000000000001",
                      "actorId":"00000000-0000-0000-0000-000000000002",
                      "status":"APPROVED",
                      "riskLevel":"LOW",
                      "idempotencyKey":"attacker-controlled",
                      "rawMessageText":"replace trusted source"
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.handoff.id").value(handoffId.toString()))
        .andExpect(jsonPath("$.handoff.status").value("CONVERTED"))
        .andExpect(jsonPath("$.draftQuote.id").value(quoteId.toString()))
        .andExpect(jsonPath("$.draftQuote.status").value("NEEDS_REVIEW"))
        .andExpect(jsonPath("$.auditStatus").value("RECORDED"))
        .andExpect(jsonPath("$.outboxStatus").value("NOT_REQUESTED"))
        .andExpect(jsonPath("$.externalWriteSafety").value("NO_EXTERNAL_WRITE"))
        .andExpect(jsonPath("$.tenantId").doesNotExist())
        .andExpect(jsonPath("$.actorId").doesNotExist())
        .andExpect(jsonPath("$.reviewerUserId").doesNotExist())
        .andExpect(jsonPath("$.sourceMessageId").doesNotExist())
        .andExpect(jsonPath("$.sourceDocumentId").doesNotExist())
        .andExpect(jsonPath("$.idempotencyKey").doesNotExist())
        .andExpect(jsonPath("$.executionStatus").doesNotExist())
        .andExpect(jsonPath("$.externalExecutionStatus").doesNotExist())
        .andExpect(jsonPath("$.structuredPayloadJson").doesNotExist())
        .andExpect(jsonPath("$.evidenceRefsJson").doesNotExist())
        .andExpect(jsonPath("$.generatedText").doesNotExist());

    verify(service)
        .createDraftQuote(handoffId, trustedActor, ActorRole.SALES_QUOTE_MANAGER);
  }

  private static RfqHandoffDraftQuoteResult result(UUID handoffId, UUID quoteId) {
    Instant now = Instant.parse("2026-07-03T00:00:00Z");
    ChannelRfqHandoffResponse handoff =
        new ChannelRfqHandoffResponse(
            handoffId,
            "TELEGRAM",
            "demo-sender",
            null,
            null,
            "Need 2 EA PAD-OE-04465 brake pads",
            "Need 2 EA PAD-OE-04465 brake pads",
            "RFQ",
            "CONVERTED",
            now,
            null,
            null,
            now,
            "Draft quote DQ-DEMO created from reviewed RFQ handoff.",
            now,
            now);
    DraftQuoteResponse quote =
        new DraftQuoteResponse(
            quoteId,
            "DQ-DEMO",
            "RFQ_HANDOFF",
            null,
            "NEEDS_REVIEW",
            "NEEDS_REVIEW",
            true,
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            now,
            List.of(),
            List.of());
    return new RfqHandoffDraftQuoteResult(handoff, quote);
  }
}
