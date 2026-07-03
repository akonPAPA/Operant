package com.orderpilot.api.rest;

import com.orderpilot.api.dto.RfqHandoffDraftQuoteDtos.RfqHandoffDraftQuoteResponse;
import com.orderpilot.application.services.channel.RfqHandoffDraftQuoteService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.security.RequestActorResolver;
import com.orderpilot.security.RequestActorRoleResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quote-owned operator action for converting a reviewed handoff into a review-required draft.
 *
 * <p>No request body is bound. Actor, role, tenant, source context, and idempotency are all
 * backend-owned.
 */
@RestController
public class RfqHandoffDraftQuoteController {
  private final RfqHandoffDraftQuoteService service;
  private final RequestActorResolver actorResolver;
  private final RequestActorRoleResolver roleResolver;

  public RfqHandoffDraftQuoteController(
      RfqHandoffDraftQuoteService service,
      RequestActorResolver actorResolver,
      RequestActorRoleResolver roleResolver) {
    this.service = service;
    this.actorResolver = actorResolver;
    this.roleResolver = roleResolver;
  }

  @PostMapping("/api/v1/quotes/drafts/from-rfq-handoff/{handoffId}")
  public RfqHandoffDraftQuoteResponse createDraftQuote(
      @PathVariable UUID handoffId, HttpServletRequest http) {
    UUID tenantId = TenantContext.requireTenantId();
    return RfqHandoffDraftQuoteResponse.from(
        service.createDraftQuote(
            handoffId,
            actorResolver.resolveVerifiedActor(http, tenantId),
            roleResolver.resolveQuoteRole()));
  }
}
