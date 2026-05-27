package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.Stage7Dtos.BotSimulateMessageResponse;
import com.orderpilot.application.services.bot.BotResponseDraftService;
import com.orderpilot.application.services.bot.BotReviewHandoffService;
import com.orderpilot.application.services.bot.BotRuntimeService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.domain.bot.BotIntent;
import com.orderpilot.domain.bot.BotPolicyDecision;
import com.orderpilot.domain.bot.BotConversation;
import com.orderpilot.domain.bot.BotResponseDraft;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BotRuntimeController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, NoopApiPermissionTestConfig.class})
class BotRuntimeControllerTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private BotRuntimeService service;
  @MockBean private BotResponseDraftService responseDraftService;
  @MockBean private BotReviewHandoffService reviewHandoffService;

  @Test
  void simulateEndpointReturnsPolicyDecisionAndSafeResponse() throws Exception {
    UUID conversationId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    when(service.simulate(any()))
        .thenReturn(new BotSimulateMessageResponse(conversationId, messageId, BotIntent.RFQ_REQUEST, BotPolicyDecision.REQUIRE_OPERATOR_REVIEW, "RFQ_REQUIRES_OPERATOR_REVIEW", "captured for review", true, UUID.randomUUID()));

    mockMvc.perform(post("/api/v1/bot-runtime/messages/simulate")
            .contentType("application/json")
            .content("{\"text\":\"Need quote for SKU-1\",\"externalChatId\":\"chat-1\",\"externalMessageId\":\"msg-1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.conversationId").value(conversationId.toString()))
        .andExpect(jsonPath("$.messageId").value(messageId.toString()))
        .andExpect(jsonPath("$.intent").value("RFQ_REQUEST"))
        .andExpect(jsonPath("$.policyDecision").value("REQUIRE_OPERATOR_REVIEW"))
        .andExpect(jsonPath("$.requiresHumanReview").value(true));
  }

  @Test
  void conversationListEndpointUsesPhase7APath() throws Exception {
    when(service.listConversations())
        .thenReturn(List.of(new BotConversation(UUID.randomUUID(), "TELEGRAM", "chat-1", Instant.parse("2026-05-26T00:00:00Z"))));

    mockMvc.perform(get("/api/v1/bot-runtime/conversations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].channel").value("TELEGRAM"))
        .andExpect(jsonPath("$[0].externalChatId").value("chat-1"));
  }

  @Test
  void responseDraftEndpointsExposeOperatorControlledStubSendFlow() throws Exception {
    UUID conversationId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    UUID responseId = UUID.randomUUID();
    BotResponseDraft draft = new BotResponseDraft(UUID.randomUUID(), conversationId, messageId, "TELEGRAM", "RFQ_REQUEST", BotPolicyDecision.REQUIRE_OPERATOR_REVIEW.name(), "We received your request and created an RFQ draft for operator review.", true, Instant.parse("2026-05-26T00:00:00Z"));
    setId(draft, responseId);
    when(responseDraftService.createDraft(any(), any(), any(Boolean.class))).thenReturn(draft);
    when(responseDraftService.markReady(any(), any())).thenReturn(draft);
    when(responseDraftService.stubSend(any())).thenReturn(draft);
    when(responseDraftService.listDrafts(any())).thenReturn(List.of(draft));

    mockMvc.perform(post("/api/v1/bot-runtime/conversations/{id}/responses/draft", conversationId)
            .contentType("application/json")
            .content("{\"sourceMessageId\":\"" + messageId + "\",\"knownCustomerIdentity\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(responseId.toString()))
        .andExpect(jsonPath("$.policyDecision").value("REQUIRE_OPERATOR_REVIEW"))
        .andExpect(jsonPath("$.requiresOperatorReview").value(true));

    mockMvc.perform(get("/api/v1/bot-runtime/conversations/{id}/responses", conversationId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].responseText").value("We received your request and created an RFQ draft for operator review."));

    mockMvc.perform(post("/api/v1/bot-runtime/responses/{id}/mark-ready", responseId)
            .contentType("application/json")
            .content("{}"))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/v1/bot-runtime/responses/{id}/stub-send", responseId))
        .andExpect(status().isOk());
  }

  private void setId(BotResponseDraft draft, UUID id) throws Exception {
    java.lang.reflect.Field field = BotResponseDraft.class.getDeclaredField("id");
    field.setAccessible(true);
    field.set(draft, id);
  }
}
