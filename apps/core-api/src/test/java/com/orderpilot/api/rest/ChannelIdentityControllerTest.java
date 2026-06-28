package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.application.services.channel.ChannelIdentityService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.tenant.TenantContextFilter;
import com.orderpilot.domain.channel.ChannelIdentity;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.RequestActorResolver;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ChannelIdentityController.class)
@Import({
    CoreConfiguration.class,
    GlobalExceptionHandler.class,
    NoopApiPermissionTestConfig.class,
    TenantContextFilter.class
})
class ChannelIdentityControllerTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private ChannelIdentityService service;
  @MockBean private RequestActorResolver actorResolver;

  @Test
  void linkUsesTrustedActorAndIgnoresBodyLinkedByUserId() throws Exception {
    UUID tenantId = UUID.randomUUID();
    UUID identityId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID trustedActor = UUID.randomUUID();
    UUID spoofActor = UUID.randomUUID();
    ChannelIdentity identity = mock(ChannelIdentity.class);
    when(identity.getId()).thenReturn(identityId);
    when(identity.getChannelType()).thenReturn("TELEGRAM");
    when(identity.getExternalSenderId()).thenReturn("sender-1");
    when(identity.getCustomerAccountId()).thenReturn(accountId);
    when(identity.getIdentityStatus()).thenReturn("LINKED");
    when(identity.getUpdatedAt()).thenReturn(Instant.parse("2026-06-28T00:00:00Z"));
    when(actorResolver.resolveVerifiedActor(any(), eq(tenantId))).thenReturn(trustedActor);
    when(service.linkIdentity(eq(identityId), eq(accountId), eq(null), any(), eq("confirmed")))
        .thenReturn(identity);

    mockMvc.perform(post("/api/v1/channel-identities/{id}/link", identityId)
            .header("X-Tenant-Id", tenantId)
            .contentType("application/json")
            .content("""
                {
                  "customerAccountId":"%s",
                  "linkedByUserId":"%s",
                  "notes":"confirmed"
                }
                """.formatted(accountId, spoofActor)))
        .andExpect(status().isOk());

    ArgumentCaptor<UUID> linkedBy = ArgumentCaptor.forClass(UUID.class);
    verify(service).linkIdentity(
        eq(identityId), eq(accountId), eq(null), linkedBy.capture(), eq("confirmed"));
    assertThat(linkedBy.getValue()).isEqualTo(trustedActor).isNotEqualTo(spoofActor);
  }
}
