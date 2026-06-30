package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage10CDtos.*;
import com.orderpilot.api.dto.Stage11EDtos.*;
import com.orderpilot.application.services.integration.ChangeRequestService;
import com.orderpilot.application.services.workspace.QuoteExternalWritePreparationService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.integration.ChangeRequest;
import com.orderpilot.domain.integration.OutboxEvent;
import com.orderpilot.security.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ChangeRequestController {
  private final ChangeRequestService service;
  private final QuoteExternalWritePreparationService externalWritePreparationService;
  private final RequestActorResolver actorResolver;

  public ChangeRequestController(ChangeRequestService service, QuoteExternalWritePreparationService externalWritePreparationService, RequestActorResolver actorResolver) {
    this.service = service;
    this.externalWritePreparationService = externalWritePreparationService;
    this.actorResolver = actorResolver;
  }

  @PostMapping("/change-requests")
  public ChangeRequestResponse create(
      @RequestBody ChangeRequestCreateRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest http) {
    // OP-CAP-17F / Wave 01H Category C: createdBy is an authority field (it stamps who originated an
    // external-write ChangeRequest). It is resolved from the trusted (optionally signed) actor
    // context, never from the request body, so a caller cannot forge the creator. The external-write
    // payload is backend-owned (a null payload defaults the domain to a neutral "{}") and idempotency
    // is taken from the standard Idempotency-Key header, never from a lower-layer body field. The body
    // carries business intent only.
    UUID createdByUserId = actorResolver.resolveVerifiedActor(http, TenantContext.getTenantId().orElse(null));
    return toChangeRequest(service.createChangeRequest(request.targetSystem(), request.targetEntity(), request.requestedAction(), request.sourceType(), request.sourceId(), null, idempotencyKey, createdByUserId));
  }

  @GetMapping("/change-requests")
  public List<ChangeRequestResponse> list() {
    return service.listChangeRequests().stream().map(this::toChangeRequest).toList();
  }

  @GetMapping("/change-requests/{id}")
  public ChangeRequestResponse get(@PathVariable UUID id) {
    return toChangeRequest(service.getChangeRequest(id));
  }

  @PostMapping("/change-requests/{id}/validate")
  public ChangeRequestResponse validate(@PathVariable UUID id) {
    return toChangeRequest(service.validateChangeRequest(id));
  }

  @PostMapping("/change-requests/{id}/approve")
  public ChangeRequestResponse approve(@PathVariable UUID id, HttpServletRequest http) {
    // OP-CAP-17E: the approver of an external-write ChangeRequest is an authority field. It is
    // resolved from the trusted (optionally signed) actor context, never from the request body, so
    // a caller cannot forge who approved an external write.
    UUID approvedByUserId = actorResolver.resolveVerifiedActor(http, TenantContext.getTenantId().orElse(null));
    return toChangeRequest(service.approveChangeRequest(id, approvedByUserId));
  }

  @PostMapping("/change-requests/{id}/approve-internal")
  public QuoteHandoffResponse approveInternal(@PathVariable UUID id, HttpServletRequest http) {
    // OP-CAP-17E: internal approval actor is server-resolved from the trusted actor context, never
    // from the request body.
    UUID actorId = actorResolver.resolveVerifiedActor(http, TenantContext.getTenantId().orElse(null));
    return externalWritePreparationService.approveInternal(id, actorId);
  }

  @PostMapping("/change-requests/{id}/cancel")
  public QuoteHandoffResponse cancel(
      @PathVariable UUID id,
      @RequestBody(required = false) LegacyChangeRequestCancelRequest request,
      HttpServletRequest http) {
    UUID actorId =
        actorResolver.resolveVerifiedActor(http, TenantContext.requireTenantId());
    return externalWritePreparationService.cancel(
        id, new ChangeRequestCancelCommand(actorId, request == null ? null : request.reason()));
  }

  @PostMapping("/change-requests/{id}/reject")
  public ChangeRequestResponse reject(@PathVariable UUID id, @RequestBody ChangeRequestRejectRequest request) {
    return toChangeRequest(service.rejectChangeRequest(id, request == null ? null : request.reason()));
  }

  @PostMapping("/change-requests/{id}/execution-disabled")
  public ChangeRequestResponse executionDisabled(@PathVariable UUID id, @RequestBody(required = false) ChangeRequestExecutionDisabledRequest request) {
    return toChangeRequest(service.markExecutionDisabled(id, request == null ? null : request.reason()));
  }

  @GetMapping("/outbox-events")
  public List<OutboxEventResponse> outboxEvents() {
    return service.listOutboxEvents().stream().map(this::toOutboxEvent).toList();
  }

  private ChangeRequestResponse toChangeRequest(ChangeRequest request) {
    // OP-CAP-31: map only operator-safe fields. Raw payload, idempotency key, payload hash, and
    // internal createdBy/approvedBy actor ids are intentionally not exposed on this response.
    return new ChangeRequestResponse(request.getId(),request.getTargetSystem(),request.getTargetEntity(),request.getRequestedAction(),request.getSourceType(),request.getSourceId(),request.getValidationStatus(),request.getApprovalStatus(),request.getCreatedAt(),request.getValidatedAt(),request.getApprovedAt(),request.getRejectedAt(),request.getExecutedAt(),request.getExternalReference(),request.getFailureReason(),request.getCancellationReason());
  }

  private OutboxEventResponse toOutboxEvent(OutboxEvent event) {
    // OP-CAP-31: the raw outbox payload is intentionally not exposed on this response.
    return new OutboxEventResponse(event.getId(), event.getAggregateType(), event.getAggregateId(), event.getEventType(), event.getStatus(), event.getCreatedAt(), event.getPublishedAt(), event.getAttemptCount(), event.getLastError());
  }
}
