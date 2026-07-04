package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.DemoRfqHandoffDtos.DemoRfqHandoffResponse;
import com.orderpilot.application.services.channel.LocalDemoRfqIntakeService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.tenant.TenantContextFilter;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.ApiPermissionGuard;
import com.orderpilot.security.ApiPermissionInterceptor;
import com.orderpilot.security.ApiSecurityWebConfig;
import com.orderpilot.security.DemoRfqHandoffRuntimeGate;
import com.orderpilot.security.RequestActorResolver;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@WebMvcTest(DemoRfqHandoffController.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "orderpilot.demo.rfq-handoff.enabled=true")
@Import({
  CoreConfiguration.class,
  GlobalExceptionHandler.class,
  ApiSecurityWebConfig.class,
  ApiPermissionInterceptor.class,
  ApiPermissionGuard.class,
  DemoRfqHandoffRuntimeGate.class,
  RequestActorResolver.class,
  TenantContextFilter.class
})
class DemoRfqHandoffControllerAuthorityBoundaryTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private LocalDemoRfqIntakeService intakeService;

  @Test
  void validCallUsesTrustedActorAndExposesOnlySafeWorkflowFields() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID trustedActor = UUID.randomUUID();
    UUID spoofActor = UUID.randomUUID();
    UUID handoffId = UUID.randomUUID();
    when(intakeService.createOrGet(trustedActor))
        .thenReturn(
            new DemoRfqHandoffResponse(
                handoffId,
                "PENDING_REVIEW",
                "Demo RFQ is ready in the RFQ handoff workspace."));

    mockMvc
        .perform(
            post("/api/v1/demo/rfq-handoff")
                .header("X-Tenant-Id", tenant.toString())
                .header(RequestActorResolver.ACTOR_HEADER, trustedActor.toString())
                .header(
                    ApiPermissionGuard.PERMISSIONS_HEADER, "ADMIN_SETTINGS_MANAGE")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "tenantId":"00000000-0000-0000-0000-000000000999",
                      "actorId":"%s",
                      "sourceId":"spoof",
                      "channelConnectionId":"00000000-0000-0000-0000-000000000998",
                      "status":"CONVERTED",
                      "approvalStatus":"APPROVED",
                      "executionStatus":"EXECUTED"
                    }
                    """
                        .formatted(spoofActor)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.handoffId").value(handoffId.toString()))
        .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
        .andExpect(jsonPath("$.message").isString())
        .andExpect(jsonPath("$.tenantId").doesNotExist())
        .andExpect(jsonPath("$.actorId").doesNotExist())
        .andExpect(jsonPath("$.sourceId").doesNotExist())
        .andExpect(jsonPath("$.channelConnectionId").doesNotExist())
        .andExpect(jsonPath("$.auditId").doesNotExist())
        .andExpect(jsonPath("$.rawPayload").doesNotExist())
        .andExpect(jsonPath("$.idempotencyKey").doesNotExist());

    ArgumentCaptor<UUID> actor = ArgumentCaptor.forClass(UUID.class);
    verify(intakeService).createOrGet(actor.capture());
    assertThat(actor.getValue()).isEqualTo(trustedActor).isNotEqualTo(spoofActor);
  }

  @Test
  void headerlessLocalDemoCallUsesBackendOwnedOperator() throws Exception {
    UUID tenant = UUID.randomUUID();

    mockMvc
        .perform(
            post("/api/v1/demo/rfq-handoff")
                .header("X-Tenant-Id", tenant.toString())
                .header(
                    ApiPermissionGuard.PERMISSIONS_HEADER, "ADMIN_SETTINGS_MANAGE"))
        .andExpect(status().isOk());

    verify(intakeService)
        .createOrGet(RequestActorResolver.LOCAL_DEMO_OPERATOR_ACTOR);
  }

  @Test
  void unrelatedAndSupportReadPermissionsAreDeniedBeforeMutation() throws Exception {
    for (String permission :
        List.of("REVIEW_ACTION", "BOT_ACTION", "STAFF_SUPPORT_READ")) {
      mockMvc
          .perform(
              post("/api/v1/demo/rfq-handoff")
                  .header("X-Tenant-Id", UUID.randomUUID().toString())
                  .header(ApiPermissionGuard.PERMISSIONS_HEADER, permission))
          .andExpect(status().isForbidden());
    }

    verifyNoInteractions(intakeService);
  }

  @Test
  void explicitSystemActorIsDeniedBeforeMutation() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/demo/rfq-handoff")
                .header("X-Tenant-Id", UUID.randomUUID().toString())
                .header(
                    RequestActorResolver.ACTOR_HEADER,
                    RequestActorResolver.SYSTEM_ACTOR.toString())
                .header(
                    ApiPermissionGuard.PERMISSIONS_HEADER, "ADMIN_SETTINGS_MANAGE"))
        .andExpect(status().isForbidden());

    verifyNoInteractions(intakeService);
  }
}
