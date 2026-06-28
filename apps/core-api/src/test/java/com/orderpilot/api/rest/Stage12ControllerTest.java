package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
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
    mockMvc.perform(get("/api/v1/channels/connections"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].providerType").value("TELEGRAM"))
        .andExpect(jsonPath("$[0].secretConfigured").value(true))
        .andExpect(jsonPath("$[0].secretRef").doesNotExist())
        .andExpect(jsonPath("$[0].secretReferenceId").doesNotExist())
        .andExpect(jsonPath("$[0].secretValue").doesNotExist());
  }

  @Test void maliciousCreateBodiesCannotSetCredentialAuthority() throws Exception {
    ChannelConnection channel = new ChannelConnection(UUID.randomUUID(), ChannelProviderType.TELEGRAM, "Telegram", "acct", "https://example.test/hook", null, Instant.now());
    IntegrationConnection integration = new IntegrationConnection(UUID.randomUUID(), IntegrationProviderType.ONE_C, "1C", "LOCAL_AGENT", null, "local", Instant.now());
    when(channelConnectionService.createDraft(any(), anyString(), any(), any(), any(), any())).thenReturn(channel);
    when(integrationConnectionService.createDraft(any(), anyString(), any(), any(), any())).thenReturn(integration);

    mockMvc.perform(post("/api/v1/channels/connections")
            .contentType("application/json")
            .content("""
                {"providerType":"TELEGRAM","displayName":"Telegram","externalAccountId":"acct",
                 "webhookUrl":"https://example.test/hook","webhookVerificationMode":"SIGNATURE_HEADER",
                 "secretRef":"attacker-ref","secretReferenceId":"attacker-id","secretValue":"attacker-secret"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.secretConfigured").value(false))
        .andExpect(jsonPath("$.secretRef").doesNotExist())
        .andExpect(jsonPath("$.secretReferenceId").doesNotExist())
        .andExpect(jsonPath("$.secretValue").doesNotExist());

    mockMvc.perform(post("/api/v1/integrations/connections")
            .contentType("application/json")
            .content("""
                {"providerType":"ONE_C","displayName":"1C","connectionKind":"LOCAL_AGENT",
                 "endpointRef":"local","secretRef":"attacker-ref",
                 "secretReferenceId":"attacker-id","secretValue":"attacker-secret"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.secretConfigured").value(false))
        .andExpect(jsonPath("$.secretRef").doesNotExist())
        .andExpect(jsonPath("$.secretReferenceId").doesNotExist())
        .andExpect(jsonPath("$.secretValue").doesNotExist());

    verify(channelConnectionService).createDraft(
        eq(ChannelProviderType.TELEGRAM), eq("Telegram"), eq("acct"),
        eq("https://example.test/hook"), isNull(), eq("SIGNATURE_HEADER"));
    verify(integrationConnectionService).createDraft(
        eq(IntegrationProviderType.ONE_C), eq("1C"), eq("LOCAL_AGENT"), isNull(), eq("local"));
  }

  @Test void credentialConfigurationResponseNeverEchoesSecretOrReference() throws Exception {
    UUID channelConnectionId = UUID.randomUUID();
    UUID integrationConnectionId = UUID.randomUUID();
    when(channelConnectionService.configureSecret(eq(channelConnectionId), eq("top-secret")))
        .thenReturn(new ChannelConnection(UUID.randomUUID(), ChannelProviderType.TELEGRAM, "Telegram", null, null, "vault:channel:1", Instant.now()));
    when(integrationConnectionService.configureSecret(eq(integrationConnectionId), eq("top-secret")))
        .thenReturn(new IntegrationConnection(UUID.randomUUID(), IntegrationProviderType.ONE_C, "1C", "LOCAL_AGENT", "vault:integration:1", "local", Instant.now()));

    mockMvc.perform(post("/api/v1/channels/connections/{id}/secret", channelConnectionId)
            .contentType("application/json")
            .content("{\"secretValue\":\"top-secret\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.secretConfigured").value(true))
        .andExpect(jsonPath("$.secretValue").doesNotExist())
        .andExpect(jsonPath("$.secretRef").doesNotExist())
        .andExpect(jsonPath("$.secretReferenceId").doesNotExist());

    mockMvc.perform(post("/api/v1/integrations/connections/{id}/secret", integrationConnectionId)
            .contentType("application/json")
            .content("{\"secretValue\":\"top-secret\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.secretConfigured").value(true))
        .andExpect(jsonPath("$.secretValue").doesNotExist())
        .andExpect(jsonPath("$.secretRef").doesNotExist())
        .andExpect(jsonPath("$.secretReferenceId").doesNotExist());
  }

  @Test void webhookEndpointStoresInboundEventResponse() throws Exception {
    UUID connectionId = UUID.randomUUID();
    // The Telegram webhook passes the parsed @RequestBody JsonNode to the (Object, Map) overload of
    // normalize(...). An untyped any() would let Java overload resolution bind this stub to the more
    // specific raw-body (String, Map) overload introduced in OP-CAP-42J, leaving the actual JsonNode
    // call unstubbed (null -> NPE -> 500). Pin the matcher to JsonNode so the stub targets the exact
    // parsed-payload overload the controller invokes.
    when(channelEventNormalizationService.normalize(eq(connectionId), eq(ChannelProviderType.TELEGRAM), any(JsonNode.class), anyMap()))
        .thenReturn(new InboundChannelEvent(UUID.randomUUID(), connectionId, ChannelProviderType.TELEGRAM, "tg-1", "CUSTOMER", "chat-1", "Need quote", "hash", "{}", Instant.now()));
    mockMvc.perform(post("/api/v1/webhooks/channels/telegram/" + connectionId).contentType("application/json").content("{\"message_id\":\"tg-1\",\"text\":\"Need quote\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.normalizedText").value("Need quote"))
        .andExpect(jsonPath("$.payloadHash").doesNotExist())
        .andExpect(jsonPath("$.errorMessage").doesNotExist())
        .andExpect(jsonPath("$.sourceActorExternalId").doesNotExist());
  }

  @Test void syncEndpointRecordsEvent() throws Exception {
    UUID connectionId = UUID.randomUUID();
    when(connectorSyncEventService.runImport(connectionId, "PRODUCT_IMPORT"))
        .thenReturn(new ConnectorSyncEvent(UUID.randomUUID(), connectionId, IntegrationProviderType.ONE_C, "PRODUCT_IMPORT", "INBOUND", Instant.now()));
    mockMvc.perform(post("/api/v1/integrations/connections/" + connectionId + "/sync/products"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.syncType").value("PRODUCT_IMPORT"))
        .andExpect(jsonPath("$.errorMessage").doesNotExist());
  }
}
