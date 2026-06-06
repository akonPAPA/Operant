package com.orderpilot.api.rest;

import com.orderpilot.api.dto.AiResultIntakeDtos.AiProcessingResultIntakeRequest;
import com.orderpilot.api.dto.AiResultIntakeDtos.AiProcessingResultIntakeResponse;
import com.orderpilot.application.services.extraction.AiWorkerResultIntakeService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-07D — internal/service-facing intake for AI-worker (OP-CAP-07C) processing results.
 *
 * <p>Not a public customer surface. Tenant is resolved server-side from {@code TenantContext}
 * ({@code X-Tenant-Id}); the route is guarded by {@code ApiPermissionInterceptor} requiring
 * {@code AI_RESULT_INTAKE} (the existing service-permission boundary — no secrets are hardcoded).
 *
 * <p>The controller is intentionally thin: all validation, correlation, persistence, idempotency and
 * audit live in {@link AiWorkerResultIntakeService}. This endpoint only persists advisory AI output;
 * it never creates or mutates quotes/orders/inventory/customers/prices/products or ERP/connector
 * state.
 */
@RestController
@RequestMapping("/api/v1/internal/ai-processing-results")
public class AiWorkerResultIntakeController {
  private final AiWorkerResultIntakeService service;

  public AiWorkerResultIntakeController(AiWorkerResultIntakeService service) {
    this.service = service;
  }

  @PostMapping
  public AiProcessingResultIntakeResponse intake(@RequestBody AiProcessingResultIntakeRequest request) {
    return service.intake(request);
  }
}
