package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.application.services.workspace.OperatorReviewService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.tenant.TenantContextFilter;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.ApiPermissionGuard;
import com.orderpilot.security.ApiPermissionInterceptor;
import com.orderpilot.security.ApiSecurityWebConfig;
import com.orderpilot.security.RequestActorResolver;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OperatorReviewController.class)
@Import({
    CoreConfiguration.class,
    GlobalExceptionHandler.class,
    ApiSecurityWebConfig.class,
    ApiPermissionInterceptor.class,
    ApiPermissionGuard.class,
    RequestActorResolver.class,
    TenantContextFilter.class
})
class OperatorReviewControllerAuthorityBoundaryTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private OperatorReviewService service;

  @Test
  void startReviewUsesTrustedActorAndIgnoresClientActorField() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID reviewCaseId = UUID.randomUUID();
    UUID trustedActor = UUID.randomUUID();
    UUID spoofActor = UUID.randomUUID();

    mockMvc.perform(post("/api/v1/operator-review/cases/{id}/start", reviewCaseId)
            .header("X-Tenant-Id", tenant.toString())
            .header(RequestActorResolver.ACTOR_HEADER, trustedActor.toString())
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "REVIEW_ACTION")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"actorUserId\":\"" + spoofActor + "\",\"status\":\"APPROVED\",\"auditOverride\":true}"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(spoofActor.toString()))));

    ArgumentCaptor<UUID> actorCaptor = ArgumentCaptor.forClass(UUID.class);
    verify(service).startReview(eq(reviewCaseId), actorCaptor.capture());
    assertThat(actorCaptor.getValue()).isEqualTo(trustedActor).isNotEqualTo(spoofActor);
  }

  @Test
  void startReviewWithoutReviewActionIsDeniedBeforeServiceInvocation() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID reviewCaseId = UUID.randomUUID();

    mockMvc.perform(post("/api/v1/operator-review/cases/{id}/start", reviewCaseId)
            .header("X-Tenant-Id", tenant.toString())
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "REVIEW_READ")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden());

    verify(service, never()).startReview(any(), any());
  }
}
