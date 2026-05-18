package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage5Dtos.ValidationRunResponse;
import com.orderpilot.application.services.validation.ValidationRunService;
import com.orderpilot.domain.validation.ValidationRun;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/extractions/results")
public class ExtractionValidationController {
  private final ValidationRunService validationRunService;
  public ExtractionValidationController(ValidationRunService validationRunService) { this.validationRunService = validationRunService; }
  @PostMapping("/{id}/run-validation")
  public ValidationRunResponse runValidation(@PathVariable UUID id) {
    ValidationRun run = validationRunService.run(id, "FULL");
    return new ValidationRunResponse(run.getId(), run.getExtractionResultId(), run.getSourceType(), run.getStatus(), run.getOverallStatus(), run.getOverallConfidence(), run.getStartedAt(), run.getFinishedAt(), run.getErrorMessage(), run.getCreatedAt());
  }
}
