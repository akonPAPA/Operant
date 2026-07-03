package com.orderpilot.api.rest;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.Stage11ADtos.DraftQuoteResponse;
import com.orderpilot.application.services.workspace.QuoteExternalWritePreparationService;
import com.orderpilot.application.services.workspace.RfqToDraftQuoteService;
import com.orderpilot.application.services.workspace.SubstituteApprovalService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.tenant.TenantContextFilter;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.ApiPermissionGuard;
import com.orderpilot.security.ApiPermissionInterceptor;
import com.orderpilot.security.ApiSecurityWebConfig;
import com.orderpilot.security.RequestActorResolver;
import com.orderpilot.security.RequestActorRoleResolver;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DraftQuoteController.class)
@Import({
  CoreConfiguration.class,
  GlobalExceptionHandler.class,
  ApiSecurityWebConfig.class,
  ApiPermissionInterceptor.class,
  ApiPermissionGuard.class,
  TenantContextFilter.class
})
class DraftQuoteListControllerSecurityTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private RfqToDraftQuoteService service;
  @MockBean private SubstituteApprovalService substituteApprovalService;
  @MockBean private QuoteExternalWritePreparationService externalWritePreparationService;
  @MockBean private RequestActorResolver actorResolver;
  @MockBean private RequestActorRoleResolver roleResolver;

  @Test
  void allowedListPassesFiltersAndPagingAndReturnsOperatorSafeArray() throws Exception {
    UUID tenantId = UUID.randomUUID();
    UUID quoteId = UUID.randomUUID();
    when(service.list("NEEDS_REVIEW", "RFQ_HANDOFF", 2, 25))
        .thenReturn(
            List.of(
                new DraftQuoteResponse(
                    quoteId,
                    "DQ-1",
                    "RFQ_HANDOFF",
                    "Acme",
                    "NEEDS_REVIEW",
                    "NEEDS_REVIEW",
                    true,
                    "USD",
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    Instant.parse("2026-07-03T00:00:00Z"),
                    List.of(),
                    List.of())));

    mockMvc
        .perform(
            get("/api/v1/quotes/drafts")
                .header("X-Tenant-Id", tenantId.toString())
                .header(ApiPermissionGuard.PERMISSIONS_HEADER, "QUOTE_READ")
                .param("status", "NEEDS_REVIEW")
                .param("sourceType", "RFQ_HANDOFF")
                .param("page", "2")
                .param("size", "25"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(quoteId.toString()))
        .andExpect(jsonPath("$[0].sourceType").value("RFQ_HANDOFF"))
        .andExpect(jsonPath("$[0].tenantId").doesNotExist())
        .andExpect(jsonPath("$[0].actorId").doesNotExist())
        .andExpect(jsonPath("$[0].sourceMessageId").doesNotExist())
        .andExpect(jsonPath("$[0].sourceDocumentId").doesNotExist())
        .andExpect(jsonPath("$[0].customerAccountId").doesNotExist())
        .andExpect(jsonPath("$[0].idempotencyKey").doesNotExist())
        .andExpect(jsonPath("$[0].generatedText").doesNotExist())
        .andExpect(jsonPath("$[0].structuredPayloadJson").doesNotExist())
        .andExpect(jsonPath("$[0].evidenceRefsJson").doesNotExist());

    verify(service).list("NEEDS_REVIEW", "RFQ_HANDOFF", 2, 25);
  }

  @Test
  void listWithoutQuoteReadPermissionIsDeniedBeforeServiceInvocation() throws Exception {
    for (String unrelatedPermission :
        List.of(
            "AUTHENTICATED_PROBE",
            "QUOTE_ACTION",
            "ADMIN_SETTINGS_READ",
            "STAFF_SUPPORT_READ")) {
      mockMvc
          .perform(
              get("/api/v1/quotes/drafts")
                  .header("X-Tenant-Id", UUID.randomUUID().toString())
                  .header(ApiPermissionGuard.PERMISSIONS_HEADER, unrelatedPermission))
          .andExpect(status().isForbidden())
          .andExpect(
              jsonPath("$.message").value("Missing required API permission QUOTE_READ"));
    }

    verifyNoInteractions(service);
  }
}
