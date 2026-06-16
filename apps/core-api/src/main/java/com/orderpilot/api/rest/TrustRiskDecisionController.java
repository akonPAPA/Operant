package com.orderpilot.api.rest;

import com.orderpilot.api.dto.TrustDtos.TrustDecisionOverrideRequest;
import com.orderpilot.api.dto.TrustDtos.TrustRiskDecisionView;
import com.orderpilot.api.dto.TrustDtos.TrustRiskEvaluationRequest;
import com.orderpilot.api.dto.TrustDtos.TrustRiskEvaluationResponse;
import com.orderpilot.application.services.trust.TrustRiskDecisionService;
import com.orderpilot.application.services.trust.TrustRiskDecisionService.EvaluateTrustRiskCommand;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.trust.TrustRiskAction;
import com.orderpilot.domain.trust.TrustRiskDecision;
import com.orderpilot.domain.trust.TrustRiskLevel;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-17D Trust Risk Decision Engine.
 *
 * Tenant-scoped risk decision surface under the existing {@code /api/v1/trust} prefix. GET reads are
 * guarded by {@code TRUST_READ}; evaluate requires {@code TRUST_RISK_EVALUATE}; override requires the
 * stronger {@code TRUST_RISK_OVERRIDE}. Tenant is resolved from context; path/body ids are never
 * trusted across tenants (all lookups are tenant-scoped in the service). No raw document/OCR/prompt
 * text, bank credentials, or secrets are ever returned.
 */
@RestController
public class TrustRiskDecisionController {
  private final TrustRiskDecisionService service;

  public TrustRiskDecisionController(TrustRiskDecisionService service) {
    this.service = service;
  }

  @PostMapping("/api/v1/trust/risk-decisions/evaluate")
  public TrustRiskEvaluationResponse evaluate(@RequestBody TrustRiskEvaluationRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    EvaluateTrustRiskCommand command = new EvaluateTrustRiskCommand(
        tenantId,
        request.subjectType(),
        request.subjectId(),
        request.documentTrustRunId(),
        request.counterpartyId(),
        request.paymentObligationId(),
        request.validationRunId(),
        request.transactionAmount(),
        request.currency(),
        request.businessAction(),
        request.idempotencyKey(),
        null,
        null);
    TrustRiskDecision decision = service.evaluate(command);
    return service.toEvaluationResponse(tenantId, decision);
  }

  @GetMapping("/api/v1/trust/risk-decisions/{id}")
  public TrustRiskDecisionView getDecision(@PathVariable UUID id) {
    return service.getDecisionView(id);
  }

  @GetMapping("/api/v1/trust/risk-decisions")
  public List<TrustRiskDecisionView> listDecisions(
      @RequestParam(name = "subjectType", required = false) String subjectType,
      @RequestParam(name = "subjectId", required = false) UUID subjectId,
      @RequestParam(name = "riskLevel", required = false) String riskLevel,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "25") int size) {
    return service.listDecisions(subjectType, subjectId, riskLevel, status, page, size);
  }

  @PostMapping("/api/v1/trust/risk-decisions/{id}/override")
  public TrustRiskDecisionView override(@PathVariable UUID id, @RequestBody TrustDecisionOverrideRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    TrustRiskLevel newLevel = parseLevel(request.newRiskLevel());
    TrustRiskAction newAction = parseAction(request.newAction());
    service.overrideDecision(tenantId, id, newLevel, newAction, request.reason(), null);
    return service.getDecisionView(id);
  }

  private TrustRiskLevel parseLevel(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("newRiskLevel is required");
    }
    try {
      return TrustRiskLevel.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unknown riskLevel: " + value);
    }
  }

  private TrustRiskAction parseAction(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return TrustRiskAction.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unknown action: " + value);
    }
  }
}
