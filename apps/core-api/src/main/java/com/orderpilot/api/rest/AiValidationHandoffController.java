package com.orderpilot.api.rest;

import com.orderpilot.api.dto.AiValidationHandoffDtos.AiValidationHandoffView;
import com.orderpilot.application.services.validation.AiValidationHandoffService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-07F AI validation handoff endpoints.
 *
 * <p>Generation is internal/service-facing ({@code POST /api/v1/internal/ai-validations/{id}/handoff},
 * guarded by {@code REVIEW_ACTION} via {@code ApiPermissionInterceptor}); the read/list views are
 * operator-facing under {@code /api/v1/ai-validation-handoffs} (guarded by {@code REVIEW_READ}).
 * Tenant is resolved server-side from {@code TenantContext}; it is never trusted from the request.
 * None of these operations creates a quote/order, mutates master data, or triggers an external write —
 * they only produce/return the advisory handoff routing record.
 */
@RestController
public class AiValidationHandoffController {
  private final AiValidationHandoffService service;

  public AiValidationHandoffController(AiValidationHandoffService service) {
    this.service = service;
  }

  @PostMapping("/api/v1/internal/ai-validations/{validationId}/handoff")
  public AiValidationHandoffView generate(@PathVariable UUID validationId) {
    return service.generate(validationId);
  }

  @GetMapping("/api/v1/ai-validation-handoffs/{handoffId}")
  public AiValidationHandoffView get(@PathVariable UUID handoffId) {
    return service.get(handoffId);
  }

  @GetMapping("/api/v1/ai-validation-handoffs")
  public List<AiValidationHandoffView> list(
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "routingDecision", required = false) String routingDecision,
      @RequestParam(name = "limit", required = false) Integer limit) {
    return service.list(status, routingDecision, limit);
  }
}
