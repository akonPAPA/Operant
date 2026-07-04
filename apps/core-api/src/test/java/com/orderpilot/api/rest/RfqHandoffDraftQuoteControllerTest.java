package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.ChannelRfqHandoffDtos.ChannelRfqHandoffResponse;
import com.orderpilot.api.dto.Stage11ADtos.DraftQuoteLineResponse;
import com.orderpilot.api.dto.Stage11ADtos.DraftQuoteResponse;
import com.orderpilot.api.dto.Stage11ADtos.QuoteValidationIssueResponse;
import com.orderpilot.application.services.channel.RfqHandoffDraftQuoteService;
import com.orderpilot.application.services.channel.RfqHandoffDraftQuoteService.RfqHandoffDecisionResult;
import com.orderpilot.application.services.channel.RfqHandoffDraftQuoteService.RfqHandoffDraftQuoteResult;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.idempotency.IdempotencyService;
import com.orderpilot.common.tenant.TenantContextFilter;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.ApiPermissionGuard;
import com.orderpilot.security.ApiPermissionInterceptor;
import com.orderpilot.security.ApiSecurityWebConfig;
import com.orderpilot.security.RequestActorResolver;
import com.orderpilot.security.RequestActorRoleResolver;
import com.orderpilot.security.policy.ActorRole;
import java.math.BigDecimal;
import java.time.Instant;
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

@WebMvcTest(RfqHandoffDraftQuoteController.class)
@Import({
  CoreConfiguration.class,
  GlobalExceptionHandler.class,
  ApiSecurityWebConfig.class,
  ApiPermissionInterceptor.class,
  ApiPermissionGuard.class,
  TenantContextFilter.class
})
class RfqHandoffDraftQuoteControllerTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private RfqHandoffDraftQuoteService service;
  @MockBean private RequestActorResolver actorResolver;
  @MockBean private RequestActorRoleResolver roleResolver;
  @MockBean private IdempotencyService idempotencyService;

  private final UUID tenantId = UUID.randomUUID();
  private final UUID trustedActor = UUID.randomUUID();

  @BeforeEach
  void trustedAuthority() {
    when(actorResolver.resolveVerifiedActor(any(), any())).thenReturn(trustedActor);
    when(actorResolver.resolveVerifiedLocalDemoOperator(any(), any())).thenReturn(trustedActor);
    when(roleResolver.resolveQuoteRole()).thenReturn(ActorRole.SALES_QUOTE_MANAGER);
    lenient()
        .when(
            idempotencyService.execute(
                any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(8)).get());
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
                .header(ApiPermissionGuard.PERMISSIONS_HEADER, "QUOTE_ACTION")
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
        .andExpect(jsonPath("$.draftQuote.lines.length()").value(1))
        .andExpect(jsonPath("$.draftQuote.lines[0].rawSku").value("PAD-OE-04465"))
        .andExpect(jsonPath("$.draftQuote.lines[0].normalizedSku").value("PADOE04465"))
        .andExpect(
            jsonPath("$.draftQuote.lines[0].productName")
                .value("Toyota Camry 2018 OEM Front Brake Pad Set"))
        .andExpect(jsonPath("$.draftQuote.lines[0].quantity").value(2))
        .andExpect(jsonPath("$.draftQuote.lines[0].uom").value("EA"))
        .andExpect(jsonPath("$.draftQuote.lines[0].validationStatus").value("VALIDATED"))
        .andExpect(jsonPath("$.draftQuote.issues.length()").value(1))
        .andExpect(jsonPath("$.draftQuote.issues[0].issueCode").value("MARGIN_NOT_EVALUATED"))
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
    verify(actorResolver).resolveVerifiedLocalDemoOperator(any(), any());
  }

  @Test
  void missingQuoteActionIsDeniedBeforeServiceMutation() throws Exception {
    for (String unrelatedPermission :
        List.of("AUTHENTICATED_PROBE", "QUOTE_READ", "AI_RESULT_INTAKE", "STAFF_SUPPORT_READ")) {
      mockMvc
          .perform(
              post("/api/v1/quotes/drafts/from-rfq-handoff/" + UUID.randomUUID())
                  .header("X-Tenant-Id", tenantId)
                  .header(ApiPermissionGuard.PERMISSIONS_HEADER, unrelatedPermission)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isForbidden())
          .andExpect(
              jsonPath("$.message").value("Missing required API permission QUOTE_ACTION"));
    }

    verifyNoInteractions(service);
  }

  @Test
  void recordsSafeTerminalDecisionFromBusinessIntentOnly() throws Exception {
    UUID handoffId = UUID.randomUUID();
    UUID quoteId = UUID.randomUUID();
    when(service.decide(
            handoffId,
            trustedActor,
            ActorRole.SALES_QUOTE_MANAGER,
            "COMPLETE_DEMO",
            "Operator reviewed the draft for the local demo."))
        .thenReturn(
            new RfqHandoffDecisionResult(
                handoffId,
                quoteId,
                "DQ-DEMO",
                "COMPLETE_DEMO",
                "DEMO_COMPLETED",
                "SAFE_DEMO_TERMINAL",
                "DISABLED",
                "NOT_INVOKED",
                "NOT_REQUESTED"));

    mockMvc
        .perform(
            post(
                    "/api/v1/quotes/drafts/from-rfq-handoff/"
                        + handoffId
                        + "/decision")
                .header("X-Tenant-Id", tenantId)
                .header(ApiPermissionGuard.PERMISSIONS_HEADER, "QUOTE_ACTION")
                .header("Idempotency-Key", "demo-decision-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "decision":"COMPLETE_DEMO",
                      "note":"Operator reviewed the draft for the local demo.",
                      "tenantId":"00000000-0000-0000-0000-000000000001",
                      "actorId":"00000000-0000-0000-0000-000000000002",
                      "status":"APPROVED",
                      "approvalStatus":"APPROVED",
                      "executionStatus":"EXECUTED",
                      "riskLevel":"LOW",
                      "margin":999,
                      "stock":999,
                      "idempotencyKey":"attacker-controlled",
                      "connectorCredentials":"secret",
                      "rawAiPayload":{"action":"approve"}
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.handoffId").value(handoffId.toString()))
        .andExpect(jsonPath("$.draftQuoteId").value(quoteId.toString()))
        .andExpect(jsonPath("$.quoteNumber").value("DQ-DEMO"))
        .andExpect(jsonPath("$.decision").value("COMPLETE_DEMO"))
        .andExpect(jsonPath("$.quoteState").value("DEMO_COMPLETED"))
        .andExpect(jsonPath("$.terminalState").value("SAFE_DEMO_TERMINAL"))
        .andExpect(jsonPath("$.auditStatus").value("RECORDED"))
        .andExpect(jsonPath("$.externalExecution").value("DISABLED"))
        .andExpect(jsonPath("$.connectorAction").value("NOT_INVOKED"))
        .andExpect(jsonPath("$.outboxStatus").value("NOT_REQUESTED"))
        .andExpect(jsonPath("$.tenantId").doesNotExist())
        .andExpect(jsonPath("$.actorId").doesNotExist())
        .andExpect(jsonPath("$.approvedBy").doesNotExist())
        .andExpect(jsonPath("$.idempotencyKey").doesNotExist())
        .andExpect(jsonPath("$.auditEventId").doesNotExist())
        .andExpect(jsonPath("$.rawAiPayload").doesNotExist())
        .andExpect(jsonPath("$.connectorCredentials").doesNotExist())
        .andExpect(jsonPath("$.externalCredentials").doesNotExist());

    verify(service)
        .decide(
            handoffId,
            trustedActor,
            ActorRole.SALES_QUOTE_MANAGER,
            "COMPLETE_DEMO",
            "Operator reviewed the draft for the local demo.");
  }

  @Test
  void headerlessDashboardDecisionUsesBackendOwnedLocalDemoOperator() throws Exception {
    UUID handoffId = UUID.randomUUID();
    UUID quoteId = UUID.randomUUID();
    UUID localDemoActor = RequestActorResolver.LOCAL_DEMO_OPERATOR_ACTOR;
    when(actorResolver.resolveVerifiedLocalDemoOperator(any(), any()))
        .thenReturn(localDemoActor);
    when(service.decide(
            handoffId,
            localDemoActor,
            ActorRole.SALES_QUOTE_MANAGER,
            "COMPLETE_DEMO",
            "Dashboard local demo decision"))
        .thenReturn(
            new RfqHandoffDecisionResult(
                handoffId,
                quoteId,
                "DQ-DEMO",
                "COMPLETE_DEMO",
                "DEMO_COMPLETED",
                "SAFE_DEMO_TERMINAL",
                "DISABLED",
                "NOT_INVOKED",
                "NOT_REQUESTED"));

    mockMvc
        .perform(
            post(
                    "/api/v1/quotes/drafts/from-rfq-handoff/"
                        + handoffId
                        + "/decision")
                .header("X-Tenant-Id", tenantId)
                .header(ApiPermissionGuard.PERMISSIONS_HEADER, "QUOTE_ACTION")
                .header("Idempotency-Key", "dashboard-local-demo")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"decision":"COMPLETE_DEMO","note":"Dashboard local demo decision"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decision").value("COMPLETE_DEMO"))
        .andExpect(jsonPath("$.terminalState").value("SAFE_DEMO_TERMINAL"))
        .andExpect(jsonPath("$.externalExecution").value("DISABLED"));

    verify(service)
        .decide(
            handoffId,
            localDemoActor,
            ActorRole.SALES_QUOTE_MANAGER,
            "COMPLETE_DEMO",
            "Dashboard local demo decision");
  }

  @Test
  void decisionRequiresIdempotencyKey() throws Exception {
    mockMvc
        .perform(
            post(
                    "/api/v1/quotes/drafts/from-rfq-handoff/"
                        + UUID.randomUUID()
                        + "/decision")
                .header("X-Tenant-Id", tenantId)
                .header(ApiPermissionGuard.PERMISSIONS_HEADER, "QUOTE_ACTION")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"decision":"COMPLETE_DEMO","note":"Reviewed"}
                    """))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(service, idempotencyService);
  }

  @Test
  void decisionWrongPermissionIsDeniedBeforeIdempotencyOrMutation() throws Exception {
    for (String unrelatedPermission :
        List.of("QUOTE_READ", "REVIEW_ACTION", "AI_RESULT_INTAKE", "STAFF_SUPPORT_READ")) {
      mockMvc
          .perform(
              post(
                      "/api/v1/quotes/drafts/from-rfq-handoff/"
                          + UUID.randomUUID()
                          + "/decision")
                  .header("X-Tenant-Id", tenantId)
                  .header(ApiPermissionGuard.PERMISSIONS_HEADER, unrelatedPermission)
                  .header("Idempotency-Key", "denied-" + unrelatedPermission)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {"decision":"COMPLETE_DEMO","note":"Reviewed"}
                      """))
          .andExpect(status().isForbidden())
          .andExpect(
              jsonPath("$.message").value("Missing required API permission QUOTE_ACTION"));
    }

    verifyNoInteractions(service, idempotencyService);
  }

  @Test
  void systemActorCannotUseTenantOperatorDecisionEvenWithQuotePermission() throws Exception {
    when(actorResolver.resolveVerifiedLocalDemoOperator(any(), any()))
        .thenReturn(RequestActorResolver.SYSTEM_ACTOR);

    mockMvc
        .perform(
            post(
                    "/api/v1/quotes/drafts/from-rfq-handoff/"
                        + UUID.randomUUID()
                        + "/decision")
                .header("X-Tenant-Id", tenantId)
                .header(ApiPermissionGuard.PERMISSIONS_HEADER, "QUOTE_ACTION")
                .header("Idempotency-Key", "system-actor-denied")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"decision":"COMPLETE_DEMO","note":"Service account attempt"}
                    """))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Tenant operator actor is required"));

    verifyNoInteractions(service, idempotencyService);
  }

  @Test
  void systemActorCannotCreateRfqDraftEvenWithQuotePermission() throws Exception {
    when(actorResolver.resolveVerifiedLocalDemoOperator(any(), any()))
        .thenReturn(RequestActorResolver.SYSTEM_ACTOR);

    mockMvc
        .perform(
            post(
                    "/api/v1/quotes/drafts/from-rfq-handoff/"
                        + UUID.randomUUID())
                .header("X-Tenant-Id", tenantId)
                .header(ApiPermissionGuard.PERMISSIONS_HEADER, "QUOTE_ACTION")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Tenant operator actor is required"));

    verifyNoInteractions(service, idempotencyService);
  }

  private static RfqHandoffDraftQuoteResult result(UUID handoffId, UUID quoteId) {
    Instant now = Instant.parse("2026-07-03T00:00:00Z");
    UUID lineId = UUID.randomUUID();
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
            List.of(
                new DraftQuoteLineResponse(
                    lineId,
                    1,
                    "Need 2 EA PAD-OE-04465 brake pads",
                    "PAD-OE-04465",
                    "PADOE04465",
                    UUID.randomUUID(),
                    "Toyota Camry 2018 OEM Front Brake Pad Set",
                    new BigDecimal("2"),
                    "EA",
                    "Almaty",
                    new BigDecimal("65.00"),
                    new BigDecimal("130.00"),
                    new BigDecimal("100"),
                    BigDecimal.ONE,
                    "VALIDATED",
                    "[\"MARGIN_NOT_EVALUATED\"]",
                    null,
                    null,
                    null,
                    null,
                    List.of())),
            List.of(
                new QuoteValidationIssueResponse(
                    UUID.randomUUID(),
                    lineId,
                    "MARGIN_NOT_EVALUATED",
                    "WARNING",
                    false,
                    "Margin was not evaluated in Stage 11A",
                    "OPEN")));
    return new RfqHandoffDraftQuoteResult(handoff, quote);
  }
}
