package com.orderpilot.api.rest;

import com.orderpilot.api.dto.RuntimeControlTelemetryDtos.RuntimeControlDemoFlowTelemetryResponse;
import com.orderpilot.application.services.runtime.RuntimeControlDemoFlowTelemetryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-27D — read-only Runtime Control Telemetry surface for the RFQ/AI/demo path.
 *
 * <p>The handler declares no {@code @RequestParam}/{@code @RequestBody}: a client cannot supply tenant,
 * actor, source, status, or runtime authority — any smuggled query/body fields are inert. Tenant scope
 * is resolved server-side from {@code TenantContext}. The route is gated by {@code ANALYTICS_READ}
 * (operator-facing read) at the {@code /api/v1/runtime-control} edge, denied before the service runs.
 */
@RestController
@RequestMapping("/api/v1/runtime-control")
public class RuntimeControlTelemetryController {
  private final RuntimeControlDemoFlowTelemetryService service;

  public RuntimeControlTelemetryController(RuntimeControlDemoFlowTelemetryService service) {
    this.service = service;
  }

  @GetMapping("/demo-flow")
  public RuntimeControlDemoFlowTelemetryResponse demoFlow() {
    return service.readDemoFlowTelemetry();
  }
}
