package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.application.services.aiwork.AiWorkService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.tenant.TenantContextFilter;
import com.orderpilot.domain.aiwork.AiWorkSourceType;
import com.orderpilot.domain.aiwork.AiWorkSuggestion;
import com.orderpilot.domain.aiwork.AiWorkType;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.RequestActorResolver;
import java.math.BigDecimal;
import java.time.Instant;
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
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, NoopApiPermissionTestConfig.class, RequestActorResolver.class, TenantContextFilter.class})
class AiWorkControllerAuthorityBoundaryTest {
  private static final String TENANT_HEADER = "X-Tenant-Id";
  private static final String ACTOR_HEADER = "X-OrderPilot-Actor-Id";
  private static final Instant NOW = Instant.parse("2026-06-14T00:00:00Z");

  @Autowired private MockMvc mockMvc;
  @MockBean private AiWorkService service;

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
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "workType": "REQUEST_SUMMARY",
                  "sourceType": "CHANNEL_MESSAGE",
                  "sourceId": "%s",
                  "contextText": "visible operator context",
                  "idempotencyKey": "idem-1",
                  "createdByUserId": "%s",
                  "status": "ACCEPTED",
                  "riskLevel": "LOW",
                  "confidence": 1,
                  "modelName": "client-selected-model",
                  "llmApproved": true
                }
                """.formatted(sourceId, spoofUser)))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString(spoofUser.toString()))))
        .andExpect(content().string(not(containsString("client-selected-model"))));

    ArgumentCaptor<UUID> actorCaptor = ArgumentCaptor.forClass(UUID.class);
    verify(service).createSuggestion(any(), any(), any(), any(), any(), actorCaptor.capture());
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
}
