package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage10CDtos.*;
import com.orderpilot.api.dto.Stage11EDtos.*;
import com.orderpilot.application.services.integration.ChangeRequestService;
import com.orderpilot.application.services.workspace.QuoteExternalWritePreparationService;
import com.orderpilot.domain.integration.ChangeRequest;
import com.orderpilot.domain.integration.OutboxEvent;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ChangeRequestController {
  private final ChangeRequestService service;
  private final QuoteExternalWritePreparationService externalWritePreparationService;

  public ChangeRequestController(ChangeRequestService service, QuoteExternalWritePreparationService externalWritePreparationService) {
    this.service = service;
    this.externalWritePreparationService = externalWritePreparationService;
  }

  @PostMapping("/change-requests")
  public ChangeRequestResponse create(@RequestBody ChangeRequestCreateRequest request) {
    return toChangeRequest(service.createChangeRequest(request.targetSystem(), request.targetEntity(), request.requestedAction(), request.sourceType(), request.sourceId(), request.requestPayloadJson(), request.idempotencyKey(), request.createdByUserId()));
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
  public ChangeRequestResponse approve(@PathVariable UUID id, @RequestBody(required = false) ChangeRequestApprovalRequest request) {
    UUID approvedByUserId = request == null ? null : request.approvedByUserId();
    return toChangeRequest(service.approveChangeRequest(id, approvedByUserId));
  }

  @PostMapping("/change-requests/{id}/approve-internal")
  public QuoteHandoffResponse approveInternal(@PathVariable UUID id, @RequestBody(required = false) QuoteHandoffCommand request) {
    return externalWritePreparationService.approveInternal(id, request == null ? null : request.actorId());
  }

  @PostMapping("/change-requests/{id}/cancel")
  public QuoteHandoffResponse cancel(@PathVariable UUID id, @RequestBody(required = false) ChangeRequestCancelCommand request) {
    return externalWritePreparationService.cancel(id, request);
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
    return new ChangeRequestResponse(request.getId(),request.getTargetSystem(),request.getTargetEntity(),request.getRequestedAction(),request.getSourceType(),request.getSourceId(),request.getRequestPayloadJson(),request.getValidationStatus(),request.getApprovalStatus(),request.getExecutionStatus(),request.getIdempotencyKey(),request.getPayloadHash(),request.getCreatedByUserId(),request.getApprovedByUserId(),request.getCreatedAt(),request.getValidatedAt(),request.getApprovedAt(),request.getRejectedAt(),request.getExecutedAt(),request.getExternalReference(),request.getFailureReason(),request.getCancellationReason());
  }

  private OutboxEventResponse toOutboxEvent(OutboxEvent event) {
    return new OutboxEventResponse(event.getId(), event.getAggregateType(), event.getAggregateId(), event.getEventType(), event.getPayloadJson(), event.getStatus(), event.getCreatedAt(), event.getPublishedAt(), event.getAttemptCount(), event.getLastError());
  }
}
