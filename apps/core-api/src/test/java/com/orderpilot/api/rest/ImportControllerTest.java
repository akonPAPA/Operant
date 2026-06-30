package com.orderpilot.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.application.services.ImportJobService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.domain.imports.ImportJob;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

@WebMvcTest(ImportController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, NoopApiPermissionTestConfig.class})
class ImportControllerTest {
  @Autowired
  private MockMvc mockMvc;

  @MockBean
  private ImportJobService service;

  @MockBean
  private RequestActorResolver actorResolver;

  @Test
  void validateMissingJobReturnsNotFound() throws Exception {
    UUID jobId = UUID.randomUUID();
    when(service.validate(jobId)).thenThrow(new NotFoundException("Import job not found"));

    mockMvc.perform(post("/api/v1/import-jobs/{jobId}/validate", jobId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("Import job not found"));
  }

  @Test
  void malformedImportRequestReturnsSafeBadRequest() throws Exception {
    mockMvc.perform(post("/api/v1/imports/PRODUCTS")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{not-json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.message").value("Request body is not valid JSON"));
  }

  @Test
  void createIgnoresBodyCreatedByAndUsesTrustedActor() throws Exception {
    UUID tenantId = UUID.randomUUID();
    UUID trustedActor = UUID.randomUUID();
    UUID maliciousActor = UUID.randomUUID();
    ImportJob job = new ImportJob(tenantId, null, "PRODUCTS", "products.csv", trustedActor, Instant.parse("2026-06-30T00:00:00Z"));
    when(actorResolver.resolveVerifiedActor(any(HttpServletRequest.class), eq(tenantId))).thenReturn(trustedActor);
    when(service.create(any(), eq(trustedActor))).thenReturn(job);

    mockMvc.perform(post("/api/v1/imports")
            .header("X-Tenant-Id", tenantId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"importType\":\"PRODUCTS\",\"originalFilename\":\"products.csv\",\"createdBy\":\""
                + maliciousActor + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.createdBy").doesNotExist())
        .andExpect(jsonPath("$.tenantId").doesNotExist());

    verify(service).create(any(), eq(trustedActor));
  }
}
