package com.orderpilot.application.services.integration;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.integration.ChangeRequest;
import com.orderpilot.domain.integration.ChangeRequestRepository;
import com.orderpilot.domain.integration.OutboxEvent;
import com.orderpilot.domain.integration.OutboxEventRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChangeRequestService {
  private final ChangeRequestRepository changeRequestRepository;
  private final OutboxEventRepository outboxEventRepository;
  private final AuditEventService auditEventService;
  private final Clock clock;

  public ChangeRequestService(ChangeRequestRepository changeRequestRepository, OutboxEventRepository outboxEventRepository, AuditEventService auditEventService, Clock clock) {
    this.changeRequestRepository = changeRequestRepository;
    this.outboxEventRepository = outboxEventRepository;
    this.auditEventService = auditEventService;
    this.clock = clock;
  }

  @Transactional
  public ChangeRequest createChangeRequest(String targetSystem, String targetEntity, String requestedAction, String sourceType, UUID sourceId, String requestPayloadJson, String idempotencyKey, UUID createdByUserId) {
    UUID tenantId = TenantContext.requireTenantId();
    requireValue(targetSystem, "targetSystem");
    requireValue(targetEntity, "targetEntity");
    requireValue(requestedAction, "requestedAction");
    requireValue(sourceType, "sourceType");
    requireValue(sourceId, "sourceId");
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      var existing = changeRequestRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
      if (existing.isPresent()) {
        return existing.get();
      }
    }
    ChangeRequest request = changeRequestRepository.save(new ChangeRequest(tenantId, targetSystem, targetEntity, requestedAction, sourceType, sourceId, requestPayloadJson, idempotencyKey, createdByUserId, clock.instant()));
    auditEventService.record("CHANGE_REQUEST_CREATED", "CHANGE_REQUEST", request.getId().toString(), createdByUserId, "{\"executionMode\":\"EXTERNAL_EXECUTION_DISABLED\"}");
    emit(request, "CHANGE_REQUEST_CREATED", "{\"executionStatus\":\"EXECUTION_DISABLED\"}");
    return request;
  }

  @Transactional
  public ChangeRequest createStage11EDraft(String targetSystem, String targetEntity, String requestedAction, String sourceType, UUID sourceId, UUID payloadSnapshotId, String requestPayloadJson, String idempotencyKey, String payloadHash, UUID createdByUserId) {
    UUID tenantId = TenantContext.requireTenantId();
    requireValue(targetSystem, "targetSystem");
    requireValue(targetEntity, "targetEntity");
    requireValue(requestedAction, "requestedAction");
    requireValue(sourceType, "sourceType");
    requireValue(sourceId, "sourceId");
    requireValue(payloadSnapshotId, "payloadSnapshotId");
    requireValue(idempotencyKey, "idempotencyKey");
    requireValue(payloadHash, "payloadHash");
    var existing = changeRequestRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
    if (existing.isPresent()) {
      return existing.get();
    }
    ChangeRequest request = changeRequestRepository.save(ChangeRequest.stage11eDraft(tenantId, targetSystem, targetEntity, requestedAction, sourceType, sourceId, payloadSnapshotId, requestPayloadJson, idempotencyKey, payloadHash, createdByUserId, clock.instant()));
    auditEventService.record("CHANGE_REQUEST_DRAFT_CREATED", "CHANGE_REQUEST", request.getId().toString(), createdByUserId, "{\"tenantId\":\"" + tenantId + "\",\"quoteId\":\"" + sourceId + "\",\"snapshotId\":\"" + payloadSnapshotId + "\",\"changeRequestId\":\"" + request.getId() + "\",\"newStatus\":\"DRAFT\",\"payloadHash\":\"" + payloadHash + "\",\"idempotencyKey\":\"" + idempotencyKey + "\",\"externalExecution\":\"DISABLED\"}");
    emit(request, "CHANGE_REQUEST_DRAFT_CREATED", "{\"executionStatus\":\"NOT_EXECUTED\",\"externalExecution\":\"DISABLED\"}");
    return request;
  }

  @Transactional
  public ChangeRequest validateChangeRequest(UUID id) {
    ChangeRequest request = getMutable(id);
    if (isObjectPayload(request.getRequestPayloadJson())) {
      request.markValidated(clock.instant());
      auditEventService.record("CHANGE_REQUEST_VALIDATED", "CHANGE_REQUEST", request.getId().toString(), null, "{}");
      emit(request, "CHANGE_REQUEST_VALIDATED", "{\"validationStatus\":\"VALIDATED\"}");
    } else {
      request.markValidationFailed("request_payload_json must be a JSON object for Stage 10C structural validation", clock.instant());
      auditEventService.record("CHANGE_REQUEST_VALIDATION_FAILED", "CHANGE_REQUEST", request.getId().toString(), null, "{\"reason\":\"STRUCTURAL_VALIDATION_FAILED\"}");
      emit(request, "CHANGE_REQUEST_VALIDATION_FAILED", "{\"validationStatus\":\"VALIDATION_FAILED\"}");
    }
    return request;
  }

  @Transactional
  public ChangeRequest approveChangeRequest(UUID id, UUID approvedByUserId) {
    ChangeRequest request = getMutable(id);
    request.approve(approvedByUserId, clock.instant());
    auditEventService.record("CHANGE_REQUEST_APPROVED", "CHANGE_REQUEST", request.getId().toString(), approvedByUserId, "{\"executionMode\":\"EXTERNAL_EXECUTION_DISABLED\"}");
    emit(request, "CHANGE_REQUEST_APPROVED", "{\"approvalStatus\":\"APPROVED\"}");
    emit(request, "CHANGE_REQUEST_EXTERNAL_EXECUTION_DISABLED", "{\"executionStatus\":\"EXECUTION_DISABLED\"}");
    return request;
  }

  @Transactional
  public ChangeRequest approveInternalStage11E(UUID id, UUID approvedByUserId) {
    ChangeRequest request = getMutable(id);
    request.approveInternal(approvedByUserId, clock.instant());
    auditEventService.record("CHANGE_REQUEST_APPROVED_INTERNAL", "CHANGE_REQUEST", request.getId().toString(), approvedByUserId, "{\"changeRequestId\":\"" + request.getId() + "\",\"quoteId\":\"" + request.getSourceId() + "\",\"snapshotId\":\"" + request.getPayloadSnapshotId() + "\",\"newStatus\":\"APPROVED_INTERNAL\",\"payloadHash\":\"" + request.getPayloadHash() + "\",\"idempotencyKey\":\"" + request.getIdempotencyKey() + "\",\"externalExecution\":\"DISABLED\"}");
    auditEventService.record("CHANGE_REQUEST_EXECUTION_BLOCKED_STAGE_11E", "CHANGE_REQUEST", request.getId().toString(), approvedByUserId, "{\"changeRequestId\":\"" + request.getId() + "\",\"reason\":\"Stage 11E does not execute connector commands\",\"executionStatus\":\"EXECUTION_DISABLED\",\"externalExecution\":\"DISABLED\"}");
    emit(request, "CHANGE_REQUEST_APPROVED_INTERNAL", "{\"approvalStatus\":\"APPROVED_INTERNAL\",\"executionStatus\":\"EXECUTION_DISABLED\"}");
    emit(request, "CHANGE_REQUEST_EXECUTION_BLOCKED_STAGE_11E", "{\"executionStatus\":\"EXECUTION_DISABLED\"}");
    return request;
  }

  @Transactional
  public ChangeRequest cancelStage11E(UUID id, UUID actorId, String reason) {
    ChangeRequest request = getMutable(id);
    request.cancel(reason == null || reason.isBlank() ? "Stage 11E ChangeRequest draft cancelled" : reason, clock.instant());
    auditEventService.record("CHANGE_REQUEST_CANCELLED", "CHANGE_REQUEST", request.getId().toString(), actorId, "{\"changeRequestId\":\"" + request.getId() + "\",\"quoteId\":\"" + request.getSourceId() + "\",\"snapshotId\":\"" + request.getPayloadSnapshotId() + "\",\"newStatus\":\"CANCELLED\",\"reason\":\"" + escape(reason) + "\",\"payloadHash\":\"" + request.getPayloadHash() + "\",\"idempotencyKey\":\"" + request.getIdempotencyKey() + "\",\"externalExecution\":\"DISABLED\"}");
    emit(request, "CHANGE_REQUEST_CANCELLED", "{\"approvalStatus\":\"CANCELLED\",\"executionStatus\":\"EXECUTION_DISABLED\"}");
    return request;
  }

  @Transactional
  public ChangeRequest blockStage11E(UUID id, String reason) {
    ChangeRequest request = getMutable(id);
    request.block(reason == null || reason.isBlank() ? "Stage 11E validation blocked ChangeRequest" : reason, clock.instant());
    auditEventService.record("CHANGE_REQUEST_EXECUTION_BLOCKED_STAGE_11E", "CHANGE_REQUEST", request.getId().toString(), null, "{\"changeRequestId\":\"" + request.getId() + "\",\"quoteId\":\"" + request.getSourceId() + "\",\"snapshotId\":\"" + request.getPayloadSnapshotId() + "\",\"newStatus\":\"BLOCKED\",\"reason\":\"" + escape(reason) + "\",\"payloadHash\":\"" + request.getPayloadHash() + "\",\"idempotencyKey\":\"" + request.getIdempotencyKey() + "\",\"externalExecution\":\"DISABLED\"}");
    emit(request, "CHANGE_REQUEST_EXECUTION_BLOCKED_STAGE_11E", "{\"validationStatus\":\"BLOCKED\",\"executionStatus\":\"EXECUTION_DISABLED\"}");
    return request;
  }

  @Transactional
  public ChangeRequest rejectChangeRequest(UUID id, String reason) {
    ChangeRequest request = getMutable(id);
    request.reject(reason == null || reason.isBlank() ? "Rejected by operator" : reason, clock.instant());
    auditEventService.record("CHANGE_REQUEST_REJECTED", "CHANGE_REQUEST", request.getId().toString(), null, "{\"executionStatus\":\"NOT_EXECUTABLE\"}");
    emit(request, "CHANGE_REQUEST_REJECTED", "{\"approvalStatus\":\"REJECTED\"}");
    return request;
  }

  @Transactional
  public ChangeRequest markExecutionDisabled(UUID id, String reason) {
    ChangeRequest request = getMutable(id);
    request.markExecutionDisabled(reason == null || reason.isBlank() ? "External connector execution is disabled in Stage 10C" : reason);
    auditEventService.record("CHANGE_REQUEST_EXTERNAL_EXECUTION_DISABLED", "CHANGE_REQUEST", request.getId().toString(), null, "{\"executionStatus\":\"EXECUTION_DISABLED\"}");
    emit(request, "CHANGE_REQUEST_EXTERNAL_EXECUTION_DISABLED", "{\"executionStatus\":\"EXECUTION_DISABLED\"}");
    return request;
  }

  @Transactional(readOnly = true)
  public List<ChangeRequest> listChangeRequests() {
    return changeRequestRepository.findByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId());
  }

  @Transactional(readOnly = true)
  public ChangeRequest getChangeRequest(UUID id) {
    return changeRequestRepository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow(() -> new NotFoundException("Change request not found: " + id));
  }

  @Transactional(readOnly = true)
  public List<OutboxEvent> listOutboxEvents() {
    return outboxEventRepository.findByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId());
  }

  private ChangeRequest getMutable(UUID id) {
    requireValue(id, "id");
    return changeRequestRepository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow(() -> new NotFoundException("Change request not found: " + id));
  }

  private OutboxEvent emit(ChangeRequest request, String eventType, String payloadJson) {
    return outboxEventRepository.save(new OutboxEvent(request.getTenantId(), "CHANGE_REQUEST", request.getId(), eventType, payloadJson, clock.instant()));
  }

  private static boolean isObjectPayload(String payload) {
    if (payload == null) {
      return false;
    }
    String trimmed = payload.trim();
    return trimmed.startsWith("{") && trimmed.endsWith("}");
  }

  private static void requireValue(Object value, String label) {
    if (value == null || (value instanceof String text && text.isBlank())) {
      throw new IllegalArgumentException(label + " is required");
    }
  }

  private static String escape(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
