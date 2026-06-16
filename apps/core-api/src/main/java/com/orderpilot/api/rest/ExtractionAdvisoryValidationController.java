package com.orderpilot.api.rest;

import com.orderpilot.api.dto.AiValidationDtos.AiValidationResultView;
import com.orderpilot.application.services.validation.ExtractionAdvisoryValidationService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-07E deterministic validation &amp; risk routing for advisory AI extraction results.
 *
 * <p>Trigger is internal/service-facing ({@code POST /api/v1/internal/extractions/{id}/validate},
 * guarded by {@code VALIDATION_RUN} via {@code ApiPermissionInterceptor}); the read view is mounted
 * under {@code /api/v1/extractions} so it inherits the existing {@code EXTRACTION_READ} guard. Tenant
 * is resolved server-side from {@code TenantContext}; it is never trusted from the request. Neither
 * operation creates a quote/order, approves anything, mutates master data, or triggers any external
 * write — they only produce/return the advisory validation routing decision.
 */
@RestController
public class ExtractionAdvisoryValidationController {
  private final ExtractionAdvisoryValidationService service;

  public ExtractionAdvisoryValidationController(ExtractionAdvisoryValidationService service) {
    this.service = service;
  }

  @PostMapping("/api/v1/internal/extractions/{extractionResultId}/validate")
  public AiValidationResultView validate(@PathVariable UUID extractionResultId) {
    return service.validate(extractionResultId);
  }

  @GetMapping("/api/v1/extractions/{extractionResultId}/validation")
  public AiValidationResultView latest(@PathVariable UUID extractionResultId) {
    return service.latest(extractionResultId);
  }
}
