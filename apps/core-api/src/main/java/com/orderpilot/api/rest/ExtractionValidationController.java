package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage5Dtos.ValidationRunResponse;
import com.orderpilot.application.services.validation.ExtractionValidationService;
import com.orderpilot.application.services.validation.ValidationRunService;
import com.orderpilot.domain.validation.ValidationRun;
import com.orderpilot.domain.validation.ExtractionValidationResult;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/extractions/results")
public class ExtractionValidationController {
  private final ValidationRunService validationRunService;
  private final ExtractionValidationService extractionValidationService;
  public ExtractionValidationController(ValidationRunService validationRunService, ExtractionValidationService extractionValidationService) { this.validationRunService = validationRunService; this.extractionValidationService = extractionValidationService; }
  @PostMapping("/{id}/run-validation")
  public ValidationRunResponse runValidation(@PathVariable UUID id) {
    ValidationRun run = validationRunService.run(id, "FULL");
    return new ValidationRunResponse(run.getId(), run.getExtractionResultId(), run.getSourceType(), run.getStatus(), run.getOverallStatus(), run.getOverallConfidence(), run.getStartedAt(), run.getFinishedAt(), run.getErrorMessage(), run.getCreatedAt());
  }
  @GetMapping("/{id}/validation")
  public ExtractionValidationResult latestValidation(@PathVariable UUID id) { return extractionValidationService.latestByExtractionResultId(id); }
}
