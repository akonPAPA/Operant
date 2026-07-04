package com.orderpilot.api.rest;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.application.services.channel.LocalDemoRfqIntakeService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.tenant.TenantContextFilter;
import com.orderpilot.domain.bot.BotRfqRequestRepository;
import com.orderpilot.domain.channel.ChannelRfqHandoffRepository;
import com.orderpilot.domain.channel.InboundChannelEventRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.ApiPermissionGuard;
import com.orderpilot.security.ApiPermissionInterceptor;
import com.orderpilot.security.ApiSecurityWebConfig;
import com.orderpilot.security.DemoRfqHandoffRuntimeGate;
import com.orderpilot.security.RequestActorResolver;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DemoRfqHandoffController.class)
@ActiveProfiles("production")
@TestPropertySource(properties = "orderpilot.demo.rfq-handoff.enabled=true")
@Import({
  CoreConfiguration.class,
  GlobalExceptionHandler.class,
  ApiSecurityWebConfig.class,
  ApiPermissionInterceptor.class,
  ApiPermissionGuard.class,
  RequestActorResolver.class,
  DemoRfqHandoffRuntimeGate.class,
  TenantContextFilter.class
})
class DemoRfqHandoffControllerProductionGateTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private LocalDemoRfqIntakeService intakeService;
  @MockBean private InboundChannelEventRepository inboundChannelEventRepository;
  @MockBean private BotRfqRequestRepository botRfqRequestRepository;
  @MockBean private ChannelRfqHandoffRepository channelRfqHandoffRepository;

  @Test
  void productionLikeRuntimeDeniesBeforeIntakeAndLeavesRfqTablesUntouched()
      throws Exception {
    mockMvc
        .perform(
            post("/api/v1/demo/rfq-handoff")
                .header("X-Tenant-Id", UUID.randomUUID().toString())
                .header(
                    RequestActorResolver.ACTOR_HEADER,
                    UUID.randomUUID().toString())
                .header(
                    ApiPermissionGuard.PERMISSIONS_HEADER,
                    "ADMIN_SETTINGS_MANAGE"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("Local demo RFQ entrypoint is disabled"));

    verifyNoInteractions(
        intakeService,
        inboundChannelEventRepository,
        botRfqRequestRepository,
        channelRfqHandoffRepository);
  }
}
