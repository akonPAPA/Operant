package com.orderpilot.api.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.application.services.ChannelMessageService;
import com.orderpilot.application.services.LegacyWebhookIngressGuard;
import com.orderpilot.application.services.WebhookEventService;
import com.orderpilot.application.services.WebhookVerificationService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.domain.intake.InboundAttachmentRepository;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LegacyWebhookControllerFailClosedTest {
  private final WebhookEventService eventService = mock(WebhookEventService.class);
  private final ChannelMessageService messageService = mock(ChannelMessageService.class);
  private final WebhookVerificationService verificationService = mock(WebhookVerificationService.class);
  private final InboundAttachmentRepository attachmentRepository = mock(InboundAttachmentRepository.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new WebhookController(
          eventService,
          messageService,
          verificationService,
          productionGuard(),
          attachmentRepository,
          objectMapper,
          Clock.systemUTC()))
      .setControllerAdvice(new GlobalExceptionHandler(Clock.systemUTC()))
      .build();

  @Test
  void productionLegacyRoutesIgnoreCallerTenantAndPersistNothing() throws Exception {
    String forgedTenant = UUID.randomUUID().toString();

    mockMvc.perform(post("/api/v1/webhooks/telegram")
            .header("X-Tenant-Id", forgedTenant)
            .header("X-Fake-Signature", "present")
            .contentType("application/json")
            .content("{\"update_id\":\"forged\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("Webhook route not found"));

    mockMvc.perform(post("/api/v1/webhooks/telegram/tenant-a")
            .header("X-Tenant-Id", forgedTenant)
            .contentType("application/json")
            .content("{\"externalEventId\":\"forged\",\"rawPayload\":\"{}\"}"))
        .andExpect(status().isNotFound());

    mockMvc.perform(post("/api/v1/webhooks/email")
            .header("X-Tenant-Id", forgedTenant)
            .contentType("application/json")
            .content("{\"externalMessageId\":\"forged\",\"bodyText\":\"forged\"}"))
        .andExpect(status().isNotFound());

    verifyNoInteractions(eventService, messageService, verificationService, attachmentRepository);
  }

  private static LegacyWebhookIngressGuard productionGuard() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");
    return new LegacyWebhookIngressGuard(environment);
  }
}
