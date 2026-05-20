package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.application.services.bot.BotRuntimeService;
import com.orderpilot.application.services.channel.TelegramSecretTokenVerifier;
import com.orderpilot.application.services.channel.WebhookSignatureVerificationResult;
import com.orderpilot.application.services.channel.WebhookVerificationMode;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BotTelegramWebhookController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class})
class BotTelegramWebhookControllerTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @MockBean private BotRuntimeService botRuntimeService;
  @MockBean private TelegramSecretTokenVerifier verifier;

  @Test
  void malformedTelegramPayloadReturnsBadRequest() throws Exception {
    when(verifier.verify(any(), any(), any(), any()))
        .thenReturn(new WebhookSignatureVerificationResult(true, WebhookVerificationMode.NOT_CONFIGURED_STAGE_10E, "TELEGRAM", "not configured"));
    when(botRuntimeService.handleTelegramUpdate(any(), any()))
        .thenThrow(new IllegalArgumentException("Malformed Telegram update payload"));

    mockMvc.perform(post("/api/v1/bot/telegram/webhook")
            .contentType("application/json")
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.message").value("Malformed Telegram update payload"));
  }

  @Test
  void invalidTelegramVerifierResultBlocksRuntimeProcessing() throws Exception {
    when(verifier.verify(any(), any(), any(), any()))
        .thenReturn(new WebhookSignatureVerificationResult(false, WebhookVerificationMode.FAILED, "TELEGRAM", "invalid secret token"));

    mockMvc.perform(post("/api/v1/bot/telegram/webhook")
            .contentType("application/json")
            .content("{\"message\":{\"chat\":{\"id\":\"chat-1\"},\"message_id\":1,\"text\":\"Need quote\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.message").value("Telegram webhook verification failed"));

    verify(botRuntimeService, never()).handleTelegramUpdate(any(), any());
  }

  @Test
  void spoofedTenantIdInTelegramPayloadIsNotUsedByControllerBoundary() throws Exception {
    when(verifier.verify(any(), any(), any(), any()))
        .thenReturn(new WebhookSignatureVerificationResult(true, WebhookVerificationMode.NOT_CONFIGURED_STAGE_10E, "TELEGRAM", "not configured"));

    mockMvc.perform(post("/api/v1/bot/telegram/webhook")
            .contentType("application/json")
            .content("{\"tenantId\":\"00000000-0000-0000-0000-000000000999\",\"message\":{\"chat\":{\"id\":\"chat-1\"},\"message_id\":1,\"text\":\"Need quote\"}}"))
        .andExpect(status().isOk());

    verify(botRuntimeService).handleTelegramUpdate(any(), any());
  }
}
