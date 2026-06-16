package com.orderpilot.api.rest;

import com.orderpilot.api.dto.WorkerJobLeaseDtos.WorkerJobClaimResponse;
import com.orderpilot.api.dto.WorkerJobLeaseDtos.WorkerJobLease;
import com.orderpilot.application.services.WorkerJobLeaseService;
import com.orderpilot.domain.intake.ProcessingJob;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-29 — internal/service-facing worker lease surface. Not a public customer surface: the route is
 * guarded by {@code ApiPermissionInterceptor} requiring {@code AI_RESULT_INTAKE} (the existing internal
 * worker permission) for any non-GET, and the tenant is resolved server-side from {@code TenantContext}.
 *
 * <p>The controller is intentionally thin — all selection, tenant scoping, the PENDING -&gt; PROCESSING
 * lease transition and bounding live in {@link WorkerJobLeaseService}. Claiming performs NO extraction and
 * invokes NO provider; it only hands the worker a bounded batch of already-admitted jobs to process.
 */
@RestController
@RequestMapping("/api/v1/internal/processing-jobs")
public class WorkerJobLeaseController {
  private final WorkerJobLeaseService service;

  public WorkerJobLeaseController(WorkerJobLeaseService service) {
    this.service = service;
  }

  @PostMapping("/claim")
  public WorkerJobClaimResponse claim(@RequestParam(value = "limit", required = false) Integer limit) {
    List<ProcessingJob> leased = service.claim(limit);
    return new WorkerJobClaimResponse(leased.size(), leased.stream().map(WorkerJobLease::from).toList());
  }
}
