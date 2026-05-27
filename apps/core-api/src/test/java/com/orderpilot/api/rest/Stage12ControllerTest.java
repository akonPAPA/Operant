package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.orderpilot.application.services.channel.*;
import com.orderpilot.application.services.integration.*;
import com.orderpilot.domain.channel.*;
import com.orderpilot.domain.integration.*;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({ChannelConnectionController.class, ChannelWebhookController.class, IntegrationConnectionController.class})
@Import({CoreConfiguration.class, NoopApiPermissionTestConfig.class})
class Stage12ControllerTest {
  @Autowired MockMvc mockMvc;
  @MockBean ChannelConnectionService channelConnectionService;
  @MockBean ChannelEventNormalizationService channelEventNormalizationService;
  @MockBean IntegrationConnectionService integrationConnectionService;
  @MockBean ConnectorSyncEventService connectorSyncEventService;

  @Test void providerCatalogEndpointsReturnExpectedProviders() throws Exception {
    mockMvc.perform(get("/api/v1/channels/providers")).andExpect(status().isOk()).andExpect(jsonPath("$[0].providerType").value("EMAIL"));
    mockMvc.perform(get("/api/v1/integrations/providers")).andExpect(status().isOk()).andExpect(jsonPath("$[0].providerType").value("ONE_C"));
  }

  @Test void channelConnectionListEndpointWorks() throws Exception {
    when(channelConnectionService.list()).thenReturn(List.of(new ChannelConnection(UUID.randomUUID(), ChannelProviderType.TELEGRAM, "Telegram", null, null, "vault:secret", Instant.now())));
    mockMvc.perform(get("/api/v1/channels/connections")).andExpect(status().isOk()).andExpect(jsonPath("$[0].providerType").value("TELEGRAM")).andExpect(jsonPath("$[0].secretConfigured").value(true)).andExpect(jsonPath("$[0].secretRef").doesNotExist());
  }

  @Test void webhookEndpointStoresInboundEventResponse() throws Exception {
    UUID connectionId = UUID.randomUUID();
    when(channelEventNormalizationService.normalize(eq(connectionId), eq(ChannelProviderType.TELEGRAM), any(), anyMap()))
        .thenReturn(new InboundChannelEvent(UUID.randomUUID(), connectionId, ChannelProviderType.TELEGRAM, "tg-1", "CUSTOMER", "chat-1", "Need quote", "hash", "{}", Instant.now()));
    mockMvc.perform(post("/api/v1/webhooks/channels/telegram/" + connectionId).contentType("application/json").content("{\"message_id\":\"tg-1\",\"text\":\"Need quote\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.normalizedText").value("Need quote"));
  }

  @Test void syncEndpointRecordsEvent() throws Exception {
    UUID connectionId = UUID.randomUUID();
    when(connectorSyncEventService.runImport(connectionId, "PRODUCT_IMPORT"))
        .thenReturn(new ConnectorSyncEvent(UUID.randomUUID(), connectionId, IntegrationProviderType.ONE_C, "PRODUCT_IMPORT", "INBOUND", Instant.now()));
    mockMvc.perform(post("/api/v1/integrations/connections/" + connectionId + "/sync/products"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.syncType").value("PRODUCT_IMPORT"));
  }
}
