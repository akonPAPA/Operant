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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.application.services.aiwork.AiWorkPublicResponseMapper;
import com.orderpilot.application.services.aiwork.AiWorkService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.tenant.TenantContextFilter;
import com.orderpilot.domain.channel.ChannelRfqHandoff;
import com.orderpilot.domain.channel.ChannelRfqHandoffRepository;
import com.orderpilot.domain.aiwork.AiWorkSourceType;
import com.orderpilot.domain.aiwork.AiWorkSuggestion;
import com.orderpilot.domain.aiwork.AiWorkType;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.RequestActorResolver;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AiWorkController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, NoopApiPermissionTestConfig.class, RequestActorResolver.class, TenantContextFilter.class, AiWorkPublicResponseMapper.class})
class AiWorkControllerAuthorityBoundaryTest {
  private static final String TENANT_HEADER = "X-Tenant-Id";
  private static final String ACTOR_HEADER = "X-OrderPilot-Actor-Id";
  private static final Instant NOW = Instant.parse("2026-06-14T00:00:00Z");

  @Autowired private MockMvc mockMvc;
  @MockBean private AiWorkService service;
  @MockBean private ChannelRfqHandoffRepository handoffRepository;

  @Test
  void createSuggestionUsesTrustedActorAndIgnoresClientSuppliedUserAndStateFields() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID trustedActor = UUID.randomUUID();
    UUID spoofUser = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();
    when(service.createSuggestion(any(), any(), any(), any(), any(), any()))
        .thenReturn(new AiWorkSuggestion(
            tenant,
            AiWorkType.REQUEST_SUMMARY,
            AiWorkSourceType.CHANNEL_MESSAGE,
            sourceId,
            "deterministic-v1",
            "LOW",
            new BigDecimal("0.80"),
            "Summary (advisory)",
            "{}",
            "[]",
            "idem-1",
            trustedActor,
            NOW));

    mockMvc.perform(post("/api/v1/ai-work/suggestions")
            .header(TENANT_HEADER, tenant.toString())
            .header(ACTOR_HEADER, trustedActor.toString())
            .header("Idempotency-Key", "idem-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "workType": "REQUEST_SUMMARY",
                  "sourceType": "CHANNEL_MESSAGE",
                  "sourceId": "%s",
                  "contextText": "visible operator context",
                  "createdByUserId": "%s",
                  "status": "ACCEPTED",
                  "riskLevel": "LOW",
                  "confidence": 1,
                  "modelName": "client-selected-model",
                  "llmApproved": true,
                  "idempotencyKey": "body-smuggled-key"
                }
                """.formatted(sourceId, spoofUser)))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString(spoofUser.toString()))))
        .andExpect(content().string(not(containsString("client-selected-model"))));

    ArgumentCaptor<String> idempotencyCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<UUID> actorCaptor = ArgumentCaptor.forClass(UUID.class);
    verify(service).createSuggestion(any(), any(), any(), any(), idempotencyCaptor.capture(), actorCaptor.capture());
    assertThat(idempotencyCaptor.getValue()).isEqualTo("idem-1");
    assertThat(actorCaptor.getValue()).isEqualTo(trustedActor);
  }

  @Test
  void acceptSuggestionUsesTrustedActorAndIgnoresClientSuppliedDecisionAuthority() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID trustedActor = UUID.randomUUID();
    UUID spoofUser = UUID.randomUUID();
    UUID suggestionId = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();
    AiWorkSuggestion accepted = new AiWorkSuggestion(
        tenant,
        AiWorkType.NEXT_ACTION_SUGGESTION,
        AiWorkSourceType.QUOTE,
        sourceId,
        "deterministic-v1",
        "MEDIUM",
        new BigDecimal("0.70"),
        "Next action (advisory)",
        "{}",
        "[]",
        null,
        trustedActor,
        NOW);
    accepted.accept(trustedActor, "operator accepted advisory note", NOW.plusSeconds(1));
    when(service.accept(any(), any(), any())).thenReturn(accepted);

    mockMvc.perform(post("/api/v1/ai-work/suggestions/{id}/accept", suggestionId)
            .header(TENANT_HEADER, tenant.toString())
            .header(ACTOR_HEADER, trustedActor.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "decidedByUserId": "%s",
                  "approvalStatus": "APPROVED",
                  "autoApprove": true,
                  "erpWriteApproved": true,
                  "reason": "operator accepted advisory note"
                }
                """.formatted(spoofUser)))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString(spoofUser.toString()))))
        .andExpect(content().string(not(containsString("erpWriteApproved"))));

    ArgumentCaptor<UUID> actorCaptor = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
    verify(service).accept(any(), actorCaptor.capture(), reasonCaptor.capture());
    assertThat(actorCaptor.getValue()).isEqualTo(trustedActor);
    assertThat(reasonCaptor.getValue()).isEqualTo("operator accepted advisory note");
  }

  @Test
  void createForRfqHandoffResolvesSourceContextFromRouteAndIgnoresSpoofedBodySource() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID trustedActor = UUID.randomUUID();
    UUID handoffId = UUID.randomUUID();
    UUID spoofSourceId = UUID.randomUUID();
    ChannelRfqHandoff handoff = new ChannelRfqHandoff(
        tenant,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "TELEGRAM",
        "external-1",
        "sender-1",
        null,
        null,
        "Customer asks for price and substitute for SKU-1",
        "RFQ",
        NOW);
    when(handoffRepository.findByIdAndTenantId(handoffId, tenant)).thenReturn(Optional.of(handoff));
    when(service.createSuggestion(any(), any(), any(), any(), any(), any()))
        .thenReturn(new AiWorkSuggestion(
            tenant,
            AiWorkType.NEXT_ACTION_SUGGESTION,
            AiWorkSourceType.RFQ_HANDOFF,
            handoffId,
            "deterministic-v1",
            "MEDIUM",
            new BigDecimal("0.50"),
            "Next action (advisory)",
            "{}",
            "[]",
            "handoff-idem",
            trustedActor,
            NOW));

    mockMvc.perform(post("/api/v1/ai-work/rfq-handoffs/{handoffId}/suggestions", handoffId)
            .header(TENANT_HEADER, tenant.toString())
            .header(ACTOR_HEADER, trustedActor.toString())
            .header("Idempotency-Key", "handoff-idem")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "workType": "NEXT_ACTION_SUGGESTION",
                  "sourceType": "QUOTE",
                  "sourceId": "%s",
                  "contextText": "client supplied context must not be used",
                  "status": "ACCEPTED",
                  "idempotencyKey": "body-smuggled"
                }
                """.formatted(spoofSourceId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.schemaVersion")
            .value("AI_WORK_SCHEMA_V1_NEXT_ACTION_SUGGESTION"))
        .andExpect(jsonPath("$.safety.advisoryOnly").value(true))
        .andExpect(jsonPath("$.safety.externalExecution").value("DISABLED"))
        .andExpect(jsonPath("$.safety.connectorCall").value("NOT_INVOKED"))
        .andExpect(jsonPath("$.safety.outbox").value("NOT_REQUESTED"))
        .andExpect(content().string(not(containsString(spoofSourceId.toString()))))
        .andExpect(content().string(not(containsString("client supplied context"))));

    ArgumentCaptor<AiWorkSourceType> sourceTypeCaptor = ArgumentCaptor.forClass(AiWorkSourceType.class);
    ArgumentCaptor<UUID> sourceIdCaptor = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<UUID> actorCaptor = ArgumentCaptor.forClass(UUID.class);
    verify(service).createSuggestion(
        eq(AiWorkType.NEXT_ACTION_SUGGESTION),
        sourceTypeCaptor.capture(),
        sourceIdCaptor.capture(),
        contextCaptor.capture(),
        eq("handoff-idem"),
        actorCaptor.capture());
    assertThat(sourceTypeCaptor.getValue()).isEqualTo(AiWorkSourceType.RFQ_HANDOFF);
    assertThat(sourceIdCaptor.getValue()).isEqualTo(handoffId).isNotEqualTo(spoofSourceId);
    assertThat(contextCaptor.getValue()).contains("RFQ_HANDOFF", "TELEGRAM", "SKU-1");
    assertThat(contextCaptor.getValue()).doesNotContain("client supplied context");
    assertThat(actorCaptor.getValue()).isEqualTo(trustedActor);
  }

  @Test
  void headerlessLocalDemoRfqAdvisoryUsesBackendOwnedOperator() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID handoffId = UUID.randomUUID();
    ChannelRfqHandoff handoff = new ChannelRfqHandoff(
        tenant,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "TELEGRAM",
        "external-local-demo",
        "sender-local-demo",
        null,
        null,
        "Need a safe RFQ advisory",
        "RFQ",
        NOW);
    when(handoffRepository.findByIdAndTenantId(handoffId, tenant)).thenReturn(Optional.of(handoff));
    when(service.createSuggestion(any(), any(), any(), any(), any(), any()))
        .thenReturn(new AiWorkSuggestion(
            tenant,
            AiWorkType.NEXT_ACTION_SUGGESTION,
            AiWorkSourceType.RFQ_HANDOFF,
            handoffId,
            "deterministic-v1",
            "LOW",
            new BigDecimal("0.75"),
            "Review the RFQ (advisory only)",
            "{}",
            "[]",
            "local-demo-rfq-advisory",
            RequestActorResolver.LOCAL_DEMO_OPERATOR_ACTOR,
            NOW));

    mockMvc
        .perform(
            post("/api/v1/ai-work/rfq-handoffs/{handoffId}/suggestions", handoffId)
                .header(TENANT_HEADER, tenant.toString())
                .header("Idempotency-Key", "local-demo-rfq-advisory")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());

    verify(service)
        .createSuggestion(
            eq(AiWorkType.NEXT_ACTION_SUGGESTION),
            eq(AiWorkSourceType.RFQ_HANDOFF),
            eq(handoffId),
            any(),
            eq("local-demo-rfq-advisory"),
            eq(RequestActorResolver.LOCAL_DEMO_OPERATOR_ACTOR));
  }

  @Test
  void explicitSystemActorCannotGenerateRfqAdvisory() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID handoffId = UUID.randomUUID();
    ChannelRfqHandoff handoff = new ChannelRfqHandoff(
        tenant,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "TELEGRAM",
        "external-system",
        "sender-system",
        null,
        null,
        "System actor attempt",
        "RFQ",
        NOW);
    when(handoffRepository.findByIdAndTenantId(handoffId, tenant)).thenReturn(Optional.of(handoff));

    mockMvc
        .perform(
            post("/api/v1/ai-work/rfq-handoffs/{handoffId}/suggestions", handoffId)
                .header(TENANT_HEADER, tenant.toString())
                .header(ACTOR_HEADER, RequestActorResolver.SYSTEM_ACTOR.toString())
                .header("Idempotency-Key", "system-rfq-advisory")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());

    verify(service, never()).createSuggestion(any(), any(), any(), any(), any(), any());
  }

  @Test
  void createForRfqHandoffRejectsTerminalSourceBeforeServiceInvocation() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID handoffId = UUID.randomUUID();
    ChannelRfqHandoff handoff = new ChannelRfqHandoff(
        tenant,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "TELEGRAM",
        "external-1",
        "sender-1",
        null,
        null,
        "Converted request",
        "RFQ",
        NOW);
    handoff.markConverted("already handled", UUID.randomUUID(), NOW.plusSeconds(1));
    when(handoffRepository.findByIdAndTenantId(handoffId, tenant)).thenReturn(Optional.of(handoff));

    mockMvc.perform(post("/api/v1/ai-work/rfq-handoffs/{handoffId}/suggestions", handoffId)
            .header(TENANT_HEADER, tenant.toString())
            .header(ACTOR_HEADER, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest());

    verify(service, never()).createSuggestion(any(), any(), any(), any(), any(), any());
  }

  @Test
  void createForRfqHandoffFromAnotherTenantIsNotFoundBeforeServiceInvocation() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID handoffId = UUID.randomUUID();
    when(handoffRepository.findByIdAndTenantId(handoffId, tenant)).thenReturn(Optional.empty());

    mockMvc.perform(post("/api/v1/ai-work/rfq-handoffs/{handoffId}/suggestions", handoffId)
            .header(TENANT_HEADER, tenant.toString())
            .header(ACTOR_HEADER, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isNotFound());

    verify(service, never()).createSuggestion(any(), any(), any(), any(), any(), any());
  }

  @Test
  void suggestionResponseScrubsInternalProviderPayloadFields() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();
    UUID suggestionId = UUID.randomUUID();
    when(service.getSuggestion(suggestionId))
        .thenReturn(new AiWorkSuggestion(
            tenant,
            AiWorkType.REQUEST_SUMMARY,
            AiWorkSourceType.CHANNEL_MESSAGE,
            sourceId,
            "deterministic-v1",
            "LOW",
            new BigDecimal("0.80"),
            "objectStorageKey=tenant/raw/private.txt",
            "{\"objectStorageKey\":\"tenant/raw/private.txt\",\"secret\":\"s3cr3t\"}",
            "[{\"promptText\":\"system prompt\",\"token\":\"secret-token\"}]",
            null,
            RequestActorResolver.SYSTEM_ACTOR,
            NOW));

    mockMvc.perform(get("/api/v1/ai-work/suggestions/{id}", suggestionId)
            .header(TENANT_HEADER, tenant.toString()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("AI suggestion could not be safely rendered.")))
        .andExpect(content().string(not(containsString("objectStorageKey"))))
        .andExpect(content().string(not(containsString("tenant/raw/private.txt"))))
        .andExpect(content().string(not(containsString("promptText"))))
        .andExpect(content().string(not(containsString("secret-token"))))
        .andExpect(content().string(not(containsString("structuredPayloadJson"))))
        .andExpect(content().string(not(containsString("evidenceRefsJson"))));
  }
}
