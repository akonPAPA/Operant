package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage4Dtos.ExtractionRunRequest;
import com.orderpilot.api.dto.Stage4Dtos.ExtractionSubmissionResponse;
import com.orderpilot.application.services.ProcessingJobService;
import com.orderpilot.application.services.extraction.ExtractionPipelineService;
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

  // OP-CAP-27c: async submission. Resolves the job server-side (tenant-scoped ownership check), then
  // routes through the runtime-control admission gate and enqueues durable work; it no longer runs
  // OCR/semantic extraction in the request thread. Returns the safe ProcessingJob/status handle.
  @PostMapping("/{id}/run-extraction")
  public ExtractionSubmissionResponse runExtraction(@PathVariable UUID id) {
    ProcessingJob job = processingJobService.get(id);
    return extractionPipelineService.submitForExtraction(
        new ExtractionRunRequest(job.getTargetType(), job.getTargetId(), job.getId(), "RULE_BASED"));
  }
}