package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.Stage12BDtos.ChannelToQuoteResponse;
import com.orderpilot.application.services.workspace.ChannelToQuoteWiringService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.tenant.TenantContextFilter;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.RequestActorResolver;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

// OP-CAP-31: the channel-to-quote conversion body cannot set the actor. actor id and actor type are
// resolved server-side (RequestActorResolver + fixed operator type), so a body-supplied actorId or
// actorType is ignored.
@WebMvcTest(QuoteTransactionConversionController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, NoopApiPermissionTestConfig.class, TenantContextFilter.class})
class ChannelToQuoteAuthorityContractTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private ChannelToQuoteWiringService wiringService;
  @MockBean private RequestActorResolver actorResolver;

  private final UUID trustedActor = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    when(actorResolver.resolveVerifiedActor(any(), any())).thenReturn(trustedActor);
  }

  @Test
  void channelMessageBodyActorIsIgnoredAndTrustedActorIsUsed() throws Exception {
    UUID messageId = UUID.randomUUID();
    UUID spoofActor = UUID.randomUUID();
    when(wiringService.createFromChannelMessage(eq(messageId), any(), any(), any()))
        .thenReturn(response(messageId));

    mockMvc.perform(post("/api/v1/quote-transactions/from-channel-message/" + messageId)
            .contentType("application/json")
            .header("X-Tenant-Id", UUID.randomUUID().toString())
            .content("{\"actorId\":\"" + spoofActor + "\",\"actorType\":\"BOT\",\"dryRun\":true}"))
        .andExpect(status().isOk());

    ArgumentCaptor<UUID> actorIdCaptor = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<String> actorTypeCaptor = ArgumentCaptor.forClass(String.class);
    verify(wiringService).createFromChannelMessage(eq(messageId), any(), actorIdCaptor.capture(), actorTypeCaptor.capture());
    assertThat(actorIdCaptor.getValue()).isEqualTo(trustedActor).isNotEqualTo(spoofActor);
    assertThat(actorTypeCaptor.getValue()).isEqualTo("USER");
  }

  @Test
  void channelToQuoteResponseDoesNotExposeInternalHandles() throws Exception {
    UUID messageId = UUID.randomUUID();
    UUID internalAttemptId = UUID.randomUUID();
    when(wiringService.createFromChannelMessage(eq(messageId), any(), any(), any()))
        .thenReturn(new ChannelToQuoteResponse("NEEDS_REVIEW", null, internalAttemptId, "CHANNEL_MESSAGE", "UNRESOLVED", 0, 0, List.of(), true));

    mockMvc.perform(post("/api/v1/quote-transactions/from-channel-message/" + messageId)
            .contentType("application/json")
            .header("X-Tenant-Id", UUID.randomUUID().toString())
            .content("{\"dryRun\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("NEEDS_REVIEW"))
        .andExpect(jsonPath("$.sourceType").value("CHANNEL_MESSAGE"))
        .andExpect(jsonPath("$.conversionAttemptId").doesNotExist())
        .andExpect(jsonPath("$.sourceId").doesNotExist())
        .andExpect(jsonPath("$.auditEventIds").doesNotExist());
  }

  private ChannelToQuoteResponse response(UUID messageId) {
    return new ChannelToQuoteResponse("NEEDS_REVIEW", null, UUID.randomUUID(), "CHANNEL_MESSAGE", "UNRESOLVED", 0, 0, List.of(), true);
  }
}
