package com.orderpilot.api.rest;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.application.services.ImportJobService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ImportController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class})
class ImportControllerTest {
  @Autowired
  private MockMvc mockMvc;

  @MockBean
  private ImportJobService service;

  @Test
  void validateMissingJobReturnsNotFound() throws Exception {
    UUID jobId = UUID.randomUUID();
    when(service.validate(jobId)).thenThrow(new NotFoundException("Import job not found"));

    mockMvc.perform(post("/api/v1/import-jobs/{jobId}/validate", jobId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("Import job not found"));
  }
}
