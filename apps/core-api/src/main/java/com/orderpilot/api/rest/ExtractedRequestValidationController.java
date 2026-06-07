package com.orderpilot.api.rest;

import com.orderpilot.api.dto.ValidationEngineDtos.ExtractedRequestValidationResult;
import com.orderpilot.api.dto.ValidationEngineDtos.ValidateExtractedRequestCommand;
import com.orderpilot.application.services.validation.ValidationEngineService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-08A deterministic validation/risk endpoint for advisory extracted requests.
 *
 * <p>Mounted under {@code /api/v1/validations} so it inherits the existing permission guard
 * ({@code VALIDATION_RUN} for non-GET). Tenant is resolved server-side from {@code TenantContext} by
 * the service; it is never trusted from the request body. This endpoint computes a deterministic
 * validation result only — it never creates a quote/order, approves anything, mutates master data,
 * or triggers any external/ERP write.
 */
@RestController
@RequestMapping("/api/v1/validations")
public class ExtractedRequestValidationController {
  private final ValidationEngineService validationEngineService;

  public ExtractedRequestValidationController(ValidationEngineService validationEngineService) {
    this.validationEngineService = validationEngineService;
  }

  @PostMapping("/extracted-request")
  public ExtractedRequestValidationResult validate(@RequestBody ValidateExtractedRequestCommand command) {
    return validationEngineService.validate(command);
  }
}
