package com.orderpilot.api.rest;

import com.orderpilot.api.dto.RfqHandoffDraftQuoteDtos.RfqHandoffDecisionRequest;
import com.orderpilot.api.dto.RfqHandoffDraftQuoteDtos.RfqHandoffDecisionResponse;
import com.orderpilot.api.dto.RfqHandoffDraftQuoteDtos.RfqHandoffDraftQuoteResponse;
import com.orderpilot.application.services.channel.RfqHandoffDraftQuoteService;
import com.orderpilot.common.idempotency.IdempotencyService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.security.RequestActorResolver;
import com.orderpilot.security.RequestActorRoleResolver;
import com.orderpilot.security.policy.TenantPolicyException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quote-owned operator action for converting a reviewed handoff into a review-required draft.
 *
 * <p>Draft creation binds no body. The terminal demo action binds only decision and note. Actor,
 * role, tenant, source context, workflow status, and execution authority are backend-owned.
 */
@RestController
public class RfqHandoffDraftQuoteController {
  private final RfqHandoffDraftQuoteService service;
  private final RequestActorResolver actorResolver;
  private final RequestActorRoleResolver roleResolver;
  private final IdempotencyService idempotencyService;

  public RfqHandoffDraftQuoteController(
      RfqHandoffDraftQuoteService service,
      RequestActorResolver actorResolver,
      RequestActorRoleResolver roleResolver,
      IdempotencyService idempotencyService) {
    this.service = service;
    this.actorResolver = actorResolver;
    this.roleResolver = roleResolver;
    this.idempotencyService = idempotencyService;
  }

  @PostMapping("/api/v1/quotes/drafts/from-rfq-handoff/{handoffId}")
  public RfqHandoffDraftQuoteResponse createDraftQuote(
      @PathVariable UUID handoffId, HttpServletRequest http) {
    UUID tenantId = TenantContext.requireTenantId();
    return RfqHandoffDraftQuoteResponse.from(
        service.createDraftQuote(
            handoffId,
            resolveTenantOperator(http, tenantId),
            roleResolver.resolveQuoteRole()));
  }

  @PostMapping("/api/v1/quotes/drafts/from-rfq-handoff/{handoffId}/decision")
  public RfqHandoffDecisionResponse decide(
      @PathVariable UUID handoffId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) RfqHandoffDecisionRequest request,
      HttpServletRequest http) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException(
          "Idempotency-Key header is required for this mutation");
    }
    UUID tenantId = TenantContext.requireTenantId();
    var role = roleResolver.resolveQuoteRole();
    UUID actorId = resolveTenantOperator(http, tenantId);
    RfqHandoffDecisionRequest businessIntent =
        request == null ? new RfqHandoffDecisionRequest(null, null) : request;
    return idempotencyService.execute(
        tenantId,
        actorId,
        idempotencyKey,
        "RFQ_HANDOFF_DEMO_DECISION",
        "DRAFT_QUOTE",
        handoffId.toString(),
        businessIntent,
        RfqHandoffDecisionResponse.class,
        () ->
            RfqHandoffDecisionResponse.from(
                service.decide(
                    handoffId,
                    actorId,
                    role,
                    businessIntent.decision(),
                    businessIntent.note())));
  }

  private UUID resolveTenantOperator(HttpServletRequest http, UUID tenantId) {
    UUID actorId = actorResolver.resolveVerifiedLocalDemoOperator(http, tenantId);
    if (RequestActorResolver.SYSTEM_ACTOR.equals(actorId)) {
      throw new TenantPolicyException("Tenant operator actor is required");
    }
    return actorId;
  }
}
