package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage11ADtos.*;
import com.orderpilot.api.dto.Stage11EDtos.*;
import com.orderpilot.application.services.workspace.RfqToDraftQuoteService;
import com.orderpilot.application.services.workspace.QuoteExternalWritePreparationService;
import com.orderpilot.application.services.workspace.SubstituteApprovalService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.security.RequestActorResolver;
import com.orderpilot.security.RequestActorRoleResolver;
import com.orderpilot.security.policy.ActorRole;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/quotes/drafts")
public class DraftQuoteController {
  private final RfqToDraftQuoteService service;
  private final SubstituteApprovalService substituteApprovalService;
  private final QuoteExternalWritePreparationService externalWritePreparationService;
  private final RequestActorResolver actorResolver;
  private final RequestActorRoleResolver roleResolver;

  public DraftQuoteController(
      RfqToDraftQuoteService service,
      SubstituteApprovalService substituteApprovalService,
      QuoteExternalWritePreparationService externalWritePreparationService,
      RequestActorResolver actorResolver,
      RequestActorRoleResolver roleResolver) {
    this.service = service;
    this.substituteApprovalService = substituteApprovalService;
    this.externalWritePreparationService = externalWritePreparationService;
    this.actorResolver = actorResolver;
    this.roleResolver = roleResolver;
  }

  @PostMapping("/from-rfq")
  public DraftQuoteResponse createFromRfq(
      @RequestBody LegacyDraftQuoteCreateRequest request,
      HttpServletRequest http) {
    TrustedQuoteAuthority authority = trustedAuthority(http);
    return service.createFromRfq(new CreateDraftQuoteFromRfqRequest(
        authority.actorId(),
        authority.role().name(),
        request.sourceType(),
        request.sourceMessageId(),
        request.sourceDocumentId(),
        request.customerHint(),
        request.rawMessageText(),
        request.lineItems()));
  }

  @GetMapping("/{id}")
  public DraftQuoteResponse get(@PathVariable UUID id) {
    return service.get(id);
  }

  @GetMapping
  public List<DraftQuoteResponse> list(@RequestParam(required = false) String status, @RequestParam(required = false) String sourceType) {
    return service.list(status, sourceType);
  }

  @PostMapping("/{id}/lines/{lineId}/substitute/approve")
  public DraftQuoteResponse approveSubstitute(
      @PathVariable UUID id,
      @PathVariable UUID lineId,
      @RequestBody LegacySubstituteDecisionRequest request,
      HttpServletRequest http) {
    return substituteApprovalService.approveSubstitute(id, lineId, substituteCommand(request, http));
  }

  @PostMapping("/{id}/lines/{lineId}/substitute/reject")
  public DraftQuoteResponse rejectSubstitute(
      @PathVariable UUID id,
      @PathVariable UUID lineId,
      @RequestBody LegacySubstituteDecisionRequest request,
      HttpServletRequest http) {
    return substituteApprovalService.rejectSubstitute(id, lineId, substituteCommand(request, http));
  }

  @PostMapping("/{id}/lines/{lineId}/substitute/reset")
  public DraftQuoteResponse resetSubstitute(
      @PathVariable UUID id,
      @PathVariable UUID lineId,
      @RequestBody(required = false) LegacySubstituteDecisionRequest request,
      HttpServletRequest http) {
    return substituteApprovalService.resetSubstituteDecision(id, lineId, substituteCommand(request, http));
  }

  @PostMapping("/{id}/mark-ready")
  public DraftQuoteResponse markReady(
      @PathVariable UUID id,
      @RequestBody(required = false) LegacyQuoteLifecycleRequest request,
      HttpServletRequest http) {
    return substituteApprovalService.markReady(id, lifecycleCommand(request, http));
  }

  @PostMapping("/{id}/approve-internal")
  public DraftQuoteResponse approveInternal(
      @PathVariable UUID id,
      @RequestBody(required = false) LegacyQuoteLifecycleRequest request,
      HttpServletRequest http) {
    return substituteApprovalService.approveQuote(id, lifecycleCommand(request, http));
  }

  @PostMapping("/{id}/reject")
  public DraftQuoteResponse rejectQuote(
      @PathVariable UUID id,
      @RequestBody(required = false) LegacyQuoteLifecycleRequest request,
      HttpServletRequest http) {
    return substituteApprovalService.rejectQuote(id, lifecycleCommand(request, http));
  }

  @PostMapping("/{id}/cancel")
  public DraftQuoteResponse cancelQuote(
      @PathVariable UUID id,
      @RequestBody(required = false) LegacyQuoteLifecycleRequest request,
      HttpServletRequest http) {
    return substituteApprovalService.cancelQuote(id, lifecycleCommand(request, http));
  }

  @PostMapping("/{id}/handoff/readiness")
  public QuoteHandoffResponse handoffReadiness(
      @PathVariable UUID id,
      @RequestBody(required = false) LegacyQuoteHandoffRequest request,
      HttpServletRequest http) {
    return externalWritePreparationService.checkReadiness(id, handoffCommand(request, http));
  }

  @PostMapping("/{id}/handoff/prepare")
  public QuoteHandoffResponse prepareHandoff(
      @PathVariable UUID id,
      @RequestBody(required = false) LegacyQuoteHandoffRequest request,
      HttpServletRequest http) {
    return externalWritePreparationService.prepareSnapshot(id, handoffCommand(request, http));
  }

  @PostMapping("/{id}/change-requests")
  public QuoteHandoffResponse createChangeRequestDraft(
      @PathVariable UUID id,
      @RequestBody(required = false) LegacyChangeRequestDraftRequest request,
      HttpServletRequest http) {
    TrustedQuoteAuthority authority = trustedAuthority(http);
    return externalWritePreparationService.createChangeRequestDraft(id, new ChangeRequestDraftCommand(
        authority.actorId(),
        request == null ? null : request.targetSystemType(),
        request == null ? null : request.targetEntityType(),
        request == null ? null : request.requestedAction()));
  }

  private SubstituteDecisionCommand substituteCommand(
      LegacySubstituteDecisionRequest request, HttpServletRequest http) {
    TrustedQuoteAuthority authority = trustedAuthority(http);
    return new SubstituteDecisionCommand(
        authority.actorId(),
        authority.role().name(),
        request == null ? null : request.substituteProductId(),
        request == null ? null : request.note());
  }

  private QuoteLifecycleCommand lifecycleCommand(
      LegacyQuoteLifecycleRequest request, HttpServletRequest http) {
    TrustedQuoteAuthority authority = trustedAuthority(http);
    return new QuoteLifecycleCommand(
        authority.actorId(),
        authority.role().name(),
        request == null ? null : request.reason());
  }

  private QuoteHandoffCommand handoffCommand(
      LegacyQuoteHandoffRequest request, HttpServletRequest http) {
    TrustedQuoteAuthority authority = trustedAuthority(http);
    return new QuoteHandoffCommand(
        authority.actorId(),
        authority.role().name(),
        request == null ? null : request.reason());
  }

  private TrustedQuoteAuthority trustedAuthority(HttpServletRequest http) {
    UUID tenantId = TenantContext.requireTenantId();
    return new TrustedQuoteAuthority(
        actorResolver.resolveVerifiedActor(http, tenantId),
        roleResolver.resolveQuoteRole());
  }

  private record TrustedQuoteAuthority(UUID actorId, ActorRole role) {}
}
