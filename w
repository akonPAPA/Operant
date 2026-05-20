warning: in the working copy of 'apps/core-api/src/main/java/com/orderpilot/api/rest/ChangeRequestController.java', LF will be replaced by CRLF the next time Git touches it
[1mdiff --git a/apps/core-api/src/main/java/com/orderpilot/api/rest/ChangeRequestController.java b/apps/core-api/src/main/java/com/orderpilot/api/rest/ChangeRequestController.java[m
[1mnew file mode 100644[m
[1mindex 0000000..c51d242[m
[1m--- /dev/null[m
[1m+++ b/apps/core-api/src/main/java/com/orderpilot/api/rest/ChangeRequestController.java[m
[36m@@ -0,0 +1,83 @@[m
[32m+[m[32mpackage com.orderpilot.api.rest;[m
[32m+[m
[32m+[m[32mimport com.orderpilot.api.dto.Stage10CDtos.*;[m
[32m+[m[32mimport com.orderpilot.api.dto.Stage11EDtos.*;[m
[32m+[m[32mimport com.orderpilot.application.services.integration.ChangeRequestService;[m
[32m+[m[32mimport com.orderpilot.application.services.workspace.QuoteExternalWritePreparationService;[m
[32m+[m[32mimport com.orderpilot.domain.integration.ChangeRequest;[m
[32m+[m[32mimport com.orderpilot.domain.integration.OutboxEvent;[m
[32m+[m[32mimport java.util.List;[m
[32m+[m[32mimport java.util.UUID;[m
[32m+[m[32mimport java.util.stream.Collectors;[m
[32m+[m[32mimport org.springframework.web.bind.annotation.*;[m
[32m+[m
[32m+[m[32m@RestController[m
[32m+[m[32m@RequestMapping("/api/v1")[m
[32m+[m[32mpublic class ChangeRequestController {[m
[32m+[m[32m  private final ChangeRequestService service;[m
[32m+[m[32m  private final QuoteExternalWritePreparationService externalWritePreparationService;[m
[32m+[m
[32m+[m[32m  public ChangeRequestController(ChangeRequestService service, QuoteExternalWritePreparationService externalWritePreparationService) {[m
[32m+[m[32m    this.service = service;[m
[32m+[m[32m    this.externalWritePreparationService = externalWritePreparationService;[m
[32m+[m[32m  }[m
[32m+[m
[32m+[m[32m  @PostMapping("/change-requests")[m
[32m+[m[32m  public ChangeRequestResponse create(@RequestBody ChangeRequestCreateRequest request) {[m
[32m+[m[32m    return toChangeRequest(service.createChangeRequest(request.targetSystem(), request.targetEntity(), request.requestedAction(), request.sourceType(), request.sourceId(), request.requestPayloadJson(), request.idempotencyKey(), request.createdByUserId()));[m
[32m+[m[32m  }[m
[32m+[m
[32m+[m[32m  @GetMapping("/change-requests")[m
[32m+[m[32m  public List<ChangeRequestResponse> list() {[m
[32m+[m[32m    return service.listChangeRequests().stream().map(this::toChangeRequest).toList();[m
[32m+[m[32m  }[m
[32m+[m
[32m+[m[32m  @GetMapping("/change-requests/{id}")[m
[32m+[m[32m  public ChangeRequestResponse get(@PathVariable UUID id) {[m
[32m+[m[32m    return toChangeRequest(service.getChangeRequest(id));[m
[32m+[m[32m  }[m
[32m+[m
[32m+[m[32m  @PostMapping("/change-requests/{id}/validate")[m
[32m+[m[32m  public ChangeRequestResponse validate(@PathVariable UUID id) {[m
[32m+[m[32m    return toChangeRequest(service.validateChangeRequest(id));[m
[32m+[m[32m  }[m
[32m+[m
[32m+[m[32m  @PostMapping("/change-requests/{id}/approve")[m
[32m+[m[32m  public ChangeRequestResponse approve(@PathVariable UUID id, @RequestBody(required = false) ChangeRequestApprovalRequest request) {[m
[32m+[m[32m    UUID approvedByUserId = request == null ? null : request.approvedByUserId();[m
[32m+[m[32m    return toChangeRequest(service.approveChangeRequest(id, approvedByUserId));[m
[32m+[m[32m  }[m
[32m+[m
[32m+[m[32m  @PostMapping("/change-requests/{id}/approve-internal")[m
[32m+[m[32m  public QuoteHandoffResponse approveInternal(@PathVariable UUID id, @RequestBody(required = false) QuoteHandoffCommand request) {[m
[32m+[m[32m    return externalWritePreparationService.approveInternal(id, request == null ? null : request.actorId());[m
[32m+[m[32m  }[m
[32m+[m
[32m+[m[32m  @PostMapping("/change-requests/{id}/cancel")[m
[32m+[m[32m  public QuoteHandoffResponse cancel(@PathVariable UUID id, @RequestBody(required = false) ChangeRequestCancelCommand request) {[m
[32m+[m[32m    return externalWritePreparationService.cancel(id, request);[m
[32m+[m[32m  }[m
[32m+[m
[32m+[m[32m  @PostMapping("/change-requests/{id}/reject")[m
[32m+[m[32m  public ChangeRequestResponse reject(@PathVariable UUID id, @RequestBody ChangeRequestRejectRequest request) {[m
[32m+[m[32m    return toChangeRequest(service.rejectChangeRequest(id, request == null ? null : request.reason()));[m
[32m+[m[32m  }[m
[32m+[m
[32m+[m[32m  @PostMapping("/change-requests/{id}/execution-disabled")[m
[32m+[m[32m  public ChangeRequestResponse executionDisabled(@PathVariable UUID id, @RequestBody(required = false) ChangeRequestExecutionDisabledRequest request) {[m
[32m+[m[32m    return toChangeRequest(service.markExecutionDisabled(id, request == null ? null : request.reason()));[m
[32m+[m[32m  }[m
[32m+[m
[32m+[m[32m  @GetMapping("/outbox-events")[m
[32m+[m[32m  public List<OutboxEventResponse> outboxEvents() {[m
[32m+[m[32m    return service.listOutboxEvents().stream().map(this::toOutboxEvent).toList();[m
[32m+[m[32m  }[m
[32m+[m
[32m+[m[32m  private ChangeRequestResponse toChangeRequest(ChangeRequest request) {[m
[32m+[m[32m    return new ChangeRequestResponse(request.getId(),request.getTargetSystem(),request.getTargetEntity(),request.getRequestedAction(),request.getSourceType(),request.getSourceId(),request.getRequestPayloadJson(),request.getValidationStatus(),request.getApprovalStatus(),request.getExecutionStatus(),request.getIdempotencyKey(),request.getPayloadHash(),request.getCreatedByUserId(),request.getApprovedByUserId(),request.getCreatedAt(),request.getValidatedAt(),request.getApprovedAt(),request.getRejectedAt(),request.getExecutedAt(),request.getExternalReference(),request.getFailureReason(),request.getCancellationReason());[m
[32m+[m[32m  }[m
[32m+[m
[32m+[m[32m  private OutboxEventResponse toOutboxEvent(OutboxEvent event) {[m
[32m+[m[32m    return new OutboxEventResponse(event.getId(), event.getAggregateType(), event.getAggregateId(), event.getEventType(), event.getPayloadJson(), event.getStatus(), event.getCreatedAt(), event.getPublishedAt(), event.getAttemptCount(), event.getLastError());[m
[32m+[m[32m  }[m
[32m+[m[32m}[m
