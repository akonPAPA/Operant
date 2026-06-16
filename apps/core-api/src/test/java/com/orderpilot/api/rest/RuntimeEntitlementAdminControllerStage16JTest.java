package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.RuntimeEntitlementAdminDtos.FeatureEntitlementResponse;
import com.orderpilot.api.dto.RuntimeEntitlementAdminDtos.TenantRuntimePlanResponse;
import com.orderpilot.application.services.runtime.RuntimeEntitlementAdminService;
import com.orderpilot.application.services.runtime.RuntimeEntitlementAdminService.CreatePlanCommand;
import com.orderpilot.application.services.runtime.RuntimeEntitlementAdminService.DisableFeatureCommand;
import com.orderpilot.domain.usage.TenantRuntimePlanCode;
import com.orderpilot.domain.usage.TenantRuntimePlanStatus;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.ApiPermissionGuard;
import com.orderpilot.security.ApiPermissionInterceptor;
import com.orderpilot.security.ApiSecurityWebConfig;
import com.orderpilot.security.RequestActorResolver;
import java.time.Instant;
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

/**
 * OP-CAP-16J — actor-resolution hardening for the runtime entitlement admin controller. The audit
 * actor is resolved from the trusted {@code X-OrderPilot-Actor-Id} header, never from the request
 * body; absent header falls back to the system actor. A body field cannot spoof the actor. Permission
 * gating is unchanged.
 */
@WebMvcTest(RuntimeEntitlementAdminController.class)
@Import({CoreConfiguration.class, ApiSecurityWebConfig.class, ApiPermissionInterceptor.class, ApiPermissionGuard.class, RequestActorResolver.class})
class RuntimeEntitlementAdminControllerStage16JTest {
  private static final String MANAGE = "RUNTIME_ENTITLEMENT_MANAGE";
  private static final String PERM_HEADER = "X-OrderPilot-Permissions";
  private static final String ACTOR_HEADER = "X-OrderPilot-Actor-Id";

  @Autowired private MockMvc mockMvc;
  @MockBean private RuntimeEntitlementAdminService service;

  private TenantRuntimePlanResponse anyPlan() {
    Instant now = Instant.parse("2026-06-13T12:00:00Z");
    return new TenantRuntimePlanResponse(UUID.randomUUID(), UUID.randomUUID(), TenantRuntimePlanCode.PRO,
        TenantRuntimePlanStatus.ACTIVE, now, null, now, now, List.of());
  }

  @Test
  void createPlanUsesActorFromTrustedHeader() throws Exception {
    UUID actor = UUID.randomUUID();
    when(service.createPlan(any())).thenReturn(anyPlan());

    mockMvc.perform(post("/api/v1/runtime/plans")
            .header(PERM_HEADER, MANAGE).header(ACTOR_HEADER, actor.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"planCode\":\"PRO\",\"status\":\"ACTIVE\"}"))
        .andExpect(status().isOk());

    ArgumentCaptor<CreatePlanCommand> captor = ArgumentCaptor.forClass(CreatePlanCommand.class);
    verify(service).createPlan(captor.capture());
    assertThat(captor.getValue().actorId()).isEqualTo(actor);
  }

  @Test
  void bodyActorIdCannotSpoofResolvedActor() throws Exception {
    UUID trustedActor = UUID.randomUUID();
    UUID spoofActor = UUID.randomUUID();
    when(service.createPlan(any())).thenReturn(anyPlan());

    // Body carries a (legacy/ignored) actorId; the trusted header must win.
    mockMvc.perform(post("/api/v1/runtime/plans")
            .header(PERM_HEADER, MANAGE).header(ACTOR_HEADER, trustedActor.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"planCode\":\"PRO\",\"status\":\"ACTIVE\",\"actorId\":\"" + spoofActor + "\"}"))
        .andExpect(status().isOk());

    ArgumentCaptor<CreatePlanCommand> captor = ArgumentCaptor.forClass(CreatePlanCommand.class);
    verify(service).createPlan(captor.capture());
    assertThat(captor.getValue().actorId()).isEqualTo(trustedActor).isNotEqualTo(spoofActor);
  }

  @Test
  void missingActorHeaderFallsBackToSystemActor() throws Exception {
    when(service.createPlan(any())).thenReturn(anyPlan());

    mockMvc.perform(post("/api/v1/runtime/plans")
            .header(PERM_HEADER, MANAGE)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"planCode\":\"PRO\",\"status\":\"ACTIVE\"}"))
        .andExpect(status().isOk());

    ArgumentCaptor<CreatePlanCommand> captor = ArgumentCaptor.forClass(CreatePlanCommand.class);
    verify(service).createPlan(captor.capture());
    assertThat(captor.getValue().actorId()).isEqualTo(RequestActorResolver.SYSTEM_ACTOR);
  }

  @Test
  void disableFeatureUsesResolvedActor() throws Exception {
    UUID actor = UUID.randomUUID();
    Instant now = Instant.parse("2026-06-13T12:00:00Z");
    when(service.disableFeatureEntitlement(any()))
        .thenReturn(new FeatureEntitlementResponse(UUID.randomUUID(), "REPORT_EXPORT", false, "revoked", now, null, now, now));

    mockMvc.perform(delete("/api/v1/runtime/plans/{planId}/features/{featureType}", UUID.randomUUID(), "REPORT_EXPORT")
            .header(PERM_HEADER, MANAGE).header(ACTOR_HEADER, actor.toString()))
        .andExpect(status().isOk());

    ArgumentCaptor<DisableFeatureCommand> captor = ArgumentCaptor.forClass(DisableFeatureCommand.class);
    verify(service).disableFeatureEntitlement(captor.capture());
    assertThat(captor.getValue().actorId()).isEqualTo(actor);
  }

  @Test
  void createPlanStillRequiresManagePermission() throws Exception {
    mockMvc.perform(post("/api/v1/runtime/plans")
            .header(PERM_HEADER, "RUNTIME_ENTITLEMENT_READ")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"planCode\":\"PRO\",\"status\":\"ACTIVE\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void invalidActorHeaderIsRejected() throws Exception {
    mockMvc.perform(post("/api/v1/runtime/plans")
            .header(PERM_HEADER, MANAGE).header(ACTOR_HEADER, "not-a-uuid")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"planCode\":\"PRO\",\"status\":\"ACTIVE\"}"))
        .andExpect(status().isBadRequest());
  }
}
