package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.application.services.workspace.DraftCommandPreparationService;
import com.orderpilot.application.services.workspace.ValidationReviewService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.tenant.TenantContextFilter;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.ApiPermissionGuard;
import com.orderpilot.security.ApiPermissionInterceptor;
import com.orderpilot.security.ApiSecurityWebConfig;
import com.orderpilot.security.RequestActorResolver;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ValidationReviewController.class)
@Import({
    CoreConfiguration.class,
    GlobalExceptionHandler.class,
    ApiSecurityWebConfig.class,
    ApiPermissionInterceptor.class,
    ApiPermissionGuard.class,
    RequestActorResolver.class,
    TenantContextFilter.class
})
class ValidationReviewControllerSecurityTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private ValidationReviewService reviewService;
  @MockBean private DraftCommandPreparationService draftCommandPreparationService;

  @Test
  void prepareDraftWithoutReviewActionIsDeniedBeforeServiceInvocation() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID reviewCaseId = UUID.randomUUID();

    mockMvc.perform(post("/api/v1/validation-review/{reviewCaseId}/prepare-draft", reviewCaseId)
            .header("X-Tenant-Id", tenant.toString())
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "REVIEW_READ")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden());

    verify(draftCommandPreparationService, never()).prepareDraft(any(), any());
  }
}
