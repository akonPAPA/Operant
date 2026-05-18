package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage4Dtos.ExtractionRunRequest;
import com.orderpilot.api.dto.Stage4Dtos.ExtractionRunResponse;
import com.orderpilot.application.services.ProcessingJobService;
import com.orderpilot.application.services.extraction.ExtractionPipelineService;
import com.orderpilot.domain.extraction.ExtractionRun;
import com.orderpilot.domain.intake.ProcessingJob;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/processing/jobs")
public class ProcessingExtractionController {
  private final ProcessingJobService processingJobService;
  private final ExtractionPipelineService extractionPipelineService;

  public ProcessingExtractionController(ProcessingJobService processingJobService, ExtractionPipelineService extractionPipelineService) {
    this.processingJobService = processingJobService;
    this.extractionPipelineService = extractionPipelineService;
  }

  @PostMapping("/{id}/run-extraction")
  public ExtractionRunResponse runExtraction(@PathVariable UUID id) {
    ProcessingJob job = processingJobService.get(id);
    ExtractionRun run = extractionPipelineService.runNow(new ExtractionRunRequest(job.getTargetType(), job.getTargetId(), job.getId(), "RULE_BASED"));
    return new ExtractionRunResponse(run.getId(), run.getSourceType(), run.getSourceId(), run.getProcessingJobId(), run.getStatus(), run.getProviderType(), run.getProviderName(), run.getSchemaVersion(), run.getCreatedAt());
  }
}