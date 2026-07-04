package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.application.services.channel.ChannelRfqHandoffService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.tenant.TenantContextFilter;
import com.orderpilot.domain.channel.ChannelRfqHandoffStatus;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.ApiPermissionGuard;
import com.orderpilot.security.ApiPermissionInterceptor;
import com.orderpilot.security.ApiSecurityWebConfig;
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

@WebMvcTest(ChannelRfqHandoffController.class)
@Import({
    CoreConfiguration.class,
    GlobalExceptionHandler.class,
    ApiSecurityWebConfig.class,
    ApiPermissionInterceptor.class,
    ApiPermissionGuard.class,
    RequestActorResolver.class,
    TenantContextFilter.class
})
class ChannelRfqHandoffControllerAuthorityBoundaryTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private ChannelRfqHandoffService handoffService;

  @Test
  void listPassesSafeFiltersAndPagingToBoundedService() throws Exception {
    UUID tenant = UUID.randomUUID();
    when(handoffService.list(ChannelRfqHandoffStatus.PENDING_REVIEW, 2, 25))
        .thenReturn(List.of());

    mockMvc
        .perform(
            get("/api/v1/channels/rfq-handoffs")
                .header("X-Tenant-Id", tenant.toString())
                .header(ApiPermissionGuard.PERMISSIONS_HEADER, "ADMIN_SETTINGS_READ")
                .param("status", "PENDING_REVIEW")
                .param("page", "2")
                .param("size", "25"))
        .andExpect(status().isOk());

    verify(handoffService).list(ChannelRfqHandoffStatus.PENDING_REVIEW, 2, 25);
  }

  @Test
  void listWithoutChannelReadPermissionIsDeniedBeforeServiceInvocation() throws Exception {
    for (String unrelatedPermission :
        List.of(
            "AUTHENTICATED_PROBE",
            "REVIEW_READ",
            "QUOTE_READ",
            "STAFF_SUPPORT_READ")) {
      mockMvc
          .perform(
              get("/api/v1/channels/rfq-handoffs")
                  .header("X-Tenant-Id", UUID.randomUUID().toString())
                  .header(ApiPermissionGuard.PERMISSIONS_HEADER, unrelatedPermission))
          .andExpect(status().isForbidden());
    }

    verifyNoInteractions(handoffService);
  }

  @Test
  void startReviewUsesTrustedActorAndIgnoresClientReviewerField() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID handoffId = UUID.randomUUID();
    UUID trustedActor = UUID.randomUUID();
    UUID spoofActor = UUID.randomUUID();

    mockMvc.perform(post("/api/v1/channels/rfq-handoffs/{id}/start-review", handoffId)
            .header("X-Tenant-Id", tenant.toString())
            .header(RequestActorResolver.ACTOR_HEADER, trustedActor.toString())
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "ADMIN_SETTINGS_MANAGE")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reviewerUserId\":\"" + spoofActor + "\",\"status\":\"CONVERTED\"}"))
        .andExpect(status().isOk());

    ArgumentCaptor<UUID> actorCaptor = ArgumentCaptor.forClass(UUID.class);
    verify(handoffService).startReview(eq(handoffId), actorCaptor.capture());
    assertThat(actorCaptor.getValue()).isEqualTo(trustedActor).isNotEqualTo(spoofActor);
  }

  @Test
  void headerlessLocalDemoStartReviewUsesBackendOwnedOperator() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID handoffId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/api/v1/channels/rfq-handoffs/{id}/start-review", handoffId)
                .header("X-Tenant-Id", tenant.toString())
                .header(ApiPermissionGuard.PERMISSIONS_HEADER, "ADMIN_SETTINGS_MANAGE")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());

    verify(handoffService)
        .startReview(handoffId, RequestActorResolver.LOCAL_DEMO_OPERATOR_ACTOR);
  }

  @Test
  void explicitSystemActorCannotMutateRfqHandoff() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID handoffId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/api/v1/channels/rfq-handoffs/{id}/start-review", handoffId)
                .header("X-Tenant-Id", tenant.toString())
                .header(
                    RequestActorResolver.ACTOR_HEADER,
                    RequestActorResolver.SYSTEM_ACTOR.toString())
                .header(ApiPermissionGuard.PERMISSIONS_HEADER, "ADMIN_SETTINGS_MANAGE")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());

    verifyNoInteractions(handoffService);
  }

  @Test
  void dismissUsesTrustedActorAndIgnoresClientActorField() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID handoffId = UUID.randomUUID();
    UUID trustedActor = UUID.randomUUID();
    UUID spoofActor = UUID.randomUUID();

    mockMvc.perform(post("/api/v1/channels/rfq-handoffs/{id}/dismiss", handoffId)
            .header("X-Tenant-Id", tenant.toString())
            .header(RequestActorResolver.ACTOR_HEADER, trustedActor.toString())
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "ADMIN_SETTINGS_MANAGE")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"not an RFQ\",\"actorUserId\":\"" + spoofActor + "\",\"approvalStatus\":\"APPROVED\"}"))
        .andExpect(status().isOk());

    verify(handoffService).dismiss(handoffId, "not an RFQ", trustedActor);
  }

  @Test
  void markConvertedUsesTrustedActorAndIgnoresClientAuthorityFields() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID handoffId = UUID.randomUUID();
    UUID trustedActor = UUID.randomUUID();
    UUID spoofActor = UUID.randomUUID();

    mockMvc.perform(post("/api/v1/channels/rfq-handoffs/{id}/mark-converted", handoffId)
            .header("X-Tenant-Id", tenant.toString())
            .header(RequestActorResolver.ACTOR_HEADER, trustedActor.toString())
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "ADMIN_SETTINGS_MANAGE")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"conversionNote\":\"done\",\"actorUserId\":\"" + spoofActor
                + "\",\"executionStatus\":\"EXECUTED\"}"))
        .andExpect(status().isOk());

    verify(handoffService).markConverted(handoffId, "done", trustedActor);
  }

  @Test
  void mutationWithoutChannelPermissionIsDeniedBeforeServiceInvocation() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID handoffId = UUID.randomUUID();

    mockMvc.perform(post("/api/v1/channels/rfq-handoffs/{id}/mark-converted", handoffId)
            .header("X-Tenant-Id", tenant.toString())
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "REVIEW_ACTION")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"conversionNote\":\"done\"}"))
        .andExpect(status().isForbidden());

    verify(handoffService, never()).markConverted(any(), any(), any());
  }
}
