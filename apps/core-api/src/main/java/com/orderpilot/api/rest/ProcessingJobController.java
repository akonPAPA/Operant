package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage3Dtos.ProcessingJobResponse;
import com.orderpilot.application.services.ProcessingJobService;
import com.orderpilot.domain.intake.ProcessingJob;
import java.util.*; import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping({"/api/v1/processing/jobs", "/api/v1/intake/jobs"})
public class ProcessingJobController {
  private final ProcessingJobService service; public ProcessingJobController(ProcessingJobService service){this.service=service;}
  @GetMapping public List<ProcessingJobResponse> list(){ return service.list().stream().map(this::toResponse).toList(); }
  @GetMapping("/{id}") public ProcessingJobResponse get(@PathVariable UUID id){ return toResponse(service.get(id)); }
  @PostMapping("/{id}/retry") public ProcessingJobResponse retry(@PathVariable UUID id){ return toResponse(service.retry(id)); }
  private ProcessingJobResponse toResponse(ProcessingJob j){ return new ProcessingJobResponse(j.getId(), j.getJobType(), j.getTargetType(), j.getTargetId(), j.getStatus(), j.getQueuedAt()); }
}
