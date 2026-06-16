package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage3Dtos.ProcessingJobResponse;
import com.orderpilot.application.services.ProcessingJobService;
import java.util.*; import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping({"/api/v1/processing/jobs", "/api/v1/intake/jobs"})
public class ProcessingJobController {
  private final ProcessingJobService service; public ProcessingJobController(ProcessingJobService service){this.service=service;}
  // OP-CAP-28: bounded, tenant-scoped, most-recent-first list. limit is optional and clamped server-side.
  @GetMapping public List<ProcessingJobResponse> list(@RequestParam(value="limit", required=false) Integer limit){ return service.list(limit).stream().map(ProcessingJobResponse::from).toList(); }
  @GetMapping("/{id}") public ProcessingJobResponse get(@PathVariable UUID id){ return ProcessingJobResponse.from(service.get(id)); }
  // OP-CAP-28: retry is fail-closed in the service (FAILED + attempts remaining only); cross-tenant => 404,
  // ineligible => 409, neither mutates. The safe status DTO is returned on success.
  @PostMapping("/{id}/retry") public ProcessingJobResponse retry(@PathVariable UUID id){ return ProcessingJobResponse.from(service.retry(id)); }
}
