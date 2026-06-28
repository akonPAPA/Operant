package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.application.services.pilot.PilotDemoScenarioService;
import com.orderpilot.application.services.pilot.PilotShadowModeService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.tenant.TenantContextFilter;
import com.orderpilot.domain.pilot.HumanCorrection;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.ApiPermissionGuard;
import com.orderpilot.security.ApiPermissionInterceptor;
import com.orderpilot.security.ApiSecurityWebConfig;
import com.orderpilot.security.RequestActorResolver;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PilotController.class)
@Import({
    CoreConfiguration.class,
    GlobalExceptionHandler.class,
    ApiSecurityWebConfig.class,
    ApiPermissionInterceptor.class,
    ApiPermissionGuard.class,
    RequestActorResolver.class,
    TenantContextFilter.class
})
class PilotControllerActorAuthorityTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private PilotShadowModeService service;
  @MockBean private PilotDemoScenarioService demoScenarioService;

  @Test
  void correctionUsesTrustedActorAndIgnoresAllBodyAuthorityFields() throws Exception {
    UUID tenantId = UUID.randomUUID();
    UUID shadowRunId = UUID.randomUUID();
    UUID trustedActor = UUID.randomUUID();
    UUID spoofActor = UUID.randomUUID();
    HumanCorrection correction = new HumanCorrection(
        tenantId,
        shadowRunId,
        trustedActor,
        "FIELD_CORRECTED",
        "{\"sku\":\"ABC\"}",
        "{\"sku\":\"ABC-1\"}",
        "Corrected SKU",
        Instant.parse("2026-06-29T00:00:00Z"));
    when(service.recordCorrection(eq(shadowRunId), any(), any(), any(), any(), any()))
        .thenReturn(correction);

    mockMvc.perform(post("/api/v1/pilot/shadow-runs/{id}/corrections", shadowRunId)
            .header("X-Tenant-Id", tenantId)
            .header(RequestActorResolver.ACTOR_HEADER, trustedActor)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "REVIEW_ACTION")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "correctionType":"FIELD_CORRECTED",
                  "beforePayloadJson":"{\\"sku\\":\\"ABC\\"}",
                  "afterPayloadJson":"{\\"sku\\":\\"ABC-1\\"}",
                  "correctionReason":"Corrected SKU",
                  "correctedByUserId":"%1$s",
                  "actorId":"%1$s",
                  "actorUserId":"%1$s",
                  "userId":"%1$s",
                  "createdBy":"%1$s",
                  "reviewedBy":"%1$s",
                  "decidedBy":"%1$s",
                  "approvedBy":"%1$s"
                }
                """.formatted(spoofActor)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.correctionType").value("FIELD_CORRECTED"))
        .andExpect(jsonPath("$.hasBeforePayload").value(true))
        .andExpect(jsonPath("$.hasAfterPayload").value(true))
        .andExpect(jsonPath("$.tenantId").doesNotExist())
        .andExpect(jsonPath("$.auditCorrelationId").doesNotExist())
        .andExpect(jsonPath("$.idempotencyKey").doesNotExist())
        .andExpect(jsonPath("$.beforePayloadJson").doesNotExist())
        .andExpect(jsonPath("$.afterPayloadJson").doesNotExist());

    ArgumentCaptor<UUID> actor = ArgumentCaptor.forClass(UUID.class);
    verify(service).recordCorrection(
        eq(shadowRunId),
        actor.capture(),
        eq("FIELD_CORRECTED"),
        eq("{\"sku\":\"ABC\"}"),
        eq("{\"sku\":\"ABC-1\"}"),
        eq("Corrected SKU"));
    assertThat(actor.getValue()).isEqualTo(trustedActor).isNotEqualTo(spoofActor);
  }

  @Test
  void correctionWithoutReviewActionIsDeniedBeforeMutation() throws Exception {
    UUID tenantId = UUID.randomUUID();
    UUID shadowRunId = UUID.randomUUID();

    mockMvc.perform(post("/api/v1/pilot/shadow-runs/{id}/corrections", shadowRunId)
            .header("X-Tenant-Id", tenantId)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "ANALYTICS_READ")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"correctionType\":\"ACCEPTED\"}"))
        .andExpect(status().isForbidden());

    verify(service, never()).recordCorrection(any(), any(), any(), any(), any(), any());
  }
}
