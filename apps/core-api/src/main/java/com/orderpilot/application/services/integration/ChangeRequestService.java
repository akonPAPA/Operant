package com.orderpilot.application.services.integration;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.integration.ChangeRequest;
import com.orderpilot.domain.integration.ChangeRequestRepository;
import com.orderpilot.domain.integration.ConnectorFailureType;
import com.orderpilot.domain.integration.ConnectorSyncEvent;
import com.orderpilot.domain.integration.ConnectorSyncEventRepository;
import com.orderpilot.domain.integration.IntegrationConnection;
import com.orderpilot.domain.integration.IntegrationConnectionRepository;
import com.orderpilot.domain.integration.IntegrationProviderType;
import com.orderpilot.domain.integration.OutboxEvent;
import com.orderpilot.domain.integration.OutboxEventRepository;
import com.orderpilot.domain.workspace.DraftOrder;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuote;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.domain.workspace.ExceptionCase;
import com.orderpilot.domain.workspace.ExceptionCaseRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChangeRequestService {
  private final ChangeRequestRepository changeRequestRepository;
  private final OutboxEventRepository outboxEventRepository;
  private final IntegrationConnectionRepository integrationConnectionRepository;
  private final ConnectorSyncEventRepository connectorSyncEventRepository;
  private final DraftQuoteRepository draftQuoteRepository;
  private final DraftOrderRepository draftOrderRepository;
  private final ExceptionCaseRepository exceptionCaseRepository;
  private final DemoErpAdapterService demoErpAdapterService = new DemoErpAdapterService();
  private final AuditEventService auditEventService;
  private final Clock clock;

  public ChangeRequestService(ChangeRequestRepository changeRequestRepository, OutboxEventRepository outboxEventRepository, IntegrationConnectionRepository integrationConnectionRepository, ConnectorSyncEventRepository connectorSyncEventRepository, DraftQuoteRepository draftQuoteRepository, DraftOrderRepository draftOrderRepository, ExceptionCaseRepository exceptionCaseRepository, AuditEventService auditEventService, Clock clock) {
    this.changeRequestRepository = changeRequestRepository;
    this.outboxEventRepository = outboxEventRepository;
    this.integrationConnectionRepository = integrationConnectionRepository;
    this.connectorSyncEventRepository = connectorSyncEventRepository;
    this.draftQuoteRepository = draftQuoteRepository;
    this.draftOrderRepository = draftOrderRepository;
    this.exceptionCaseRepository = exceptionCaseRepository;
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
  public ChangeRequest createStage9DemoChangeRequest(String sourceType, UUID sourceId, String requestedAction, String requestPayloadJson, UUID actorId) {
    UUID tenantId = TenantContext.requireTenantId();
    requireValue(sourceType, "sourceType");
    requireValue(sourceId, "sourceId");
    String normalizedSourceType = sourceType.trim().toUpperCase();
    String normalizedAction = requestedAction == null || requestedAction.isBlank()
        ? ("DRAFT_ORDER".equals(normalizedSourceType) ? "CREATE_DRAFT_ORDER" : "CREATE_DRAFT_QUOTE")
        : requestedAction.trim().toUpperCase();
    if ("DRAFT_QUOTE".equals(normalizedSourceType)) {
      DraftQuote quote = draftQuoteRepository.findByIdAndTenantId(sourceId, tenantId).orElseThrow(() -> new IllegalArgumentException("Draft quote not found or not tenant-owned"));
      requireEligibleQuote(quote);
      requireValidationBackedCase(quote.getSourceExceptionCaseId());
    } else if ("DRAFT_ORDER".equals(normalizedSourceType)) {
      DraftOrder order = draftOrderRepository.findByIdAndTenantId(sourceId, tenantId).orElseThrow(() -> new IllegalArgumentException("Draft order not found or not tenant-owned"));
      requireEligibleOrder(order);
      requireValidationBackedCase(order.getSourceExceptionCaseId());
    } else {
      throw new IllegalArgumentException("Stage 9A ChangeRequest sourceType must be DRAFT_QUOTE or DRAFT_ORDER");
    }
    String targetEntity = "DRAFT_ORDER".equals(normalizedSourceType) ? "DRAFT_ORDER" : "DRAFT_QUOTE";
    String idempotencyKey = "stage9a:" + sha256(tenantId + ":" + normalizedSourceType + ":" + sourceId + ":" + normalizedAction);
    return createChangeRequest("DEMO_ERP", targetEntity, normalizedAction, normalizedSourceType, sourceId, requestPayloadJson, idempotencyKey, actorId);
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
  public ChangeRequest executeStage9DemoChangeRequest(UUID id) {
    ChangeRequest request = getMutable(id);
    if ("EXECUTED".equals(request.getExecutionStatus()) && request.getExternalReference() != null) {
      auditEventService.record("DEMO_ERP_IDEMPOTENT_REPLAY", "CHANGE_REQUEST", request.getId().toString(), null, "{\"externalReference\":\"" + request.getExternalReference() + "\",\"idempotencyKeyHash\":\"" + connectorIdempotencyKeyHash(request) + "\",\"replay\":true,\"networkCall\":false}");
      return request;
    }
    if ("CANCELLED".equals(request.getExecutionStatus()) || "CANCELLED".equals(request.getApprovalStatus())) {
      auditPolicyBlock(request, "CHANGE_REQUEST_CANCELLED");
      throw new IllegalStateException("Cancelled ChangeRequest cannot execute");
    }
    if ("FAILED".equals(request.getExecutionStatus())) {
      auditPolicyBlock(request, "FAILED_REQUIRES_EXPLICIT_RETRY");
      throw new IllegalStateException("Failed ChangeRequest requires explicit retry");
    }
    if (!"APPROVED".equals(request.getApprovalStatus())) {
      auditPolicyBlock(request, "APPROVAL_REQUIRED");
      throw new IllegalStateException("ChangeRequest must be APPROVED before demo execution");
    }
    if (!"DEMO_ERP".equals(request.getTargetSystem())) {
      auditPolicyBlock(request, "NON_DEMO_TARGET_BLOCKED");
      throw new IllegalStateException("Stage 9A only supports the demo ERP adapter");
    }
    if (!List.of("DRAFT_QUOTE", "DRAFT_ORDER").contains(request.getSourceType())) {
      auditPolicyBlock(request, "UNSUPPORTED_SOURCE_TYPE");
      throw new IllegalStateException("Stage 9A only executes draft quote/order ChangeRequests");
    }
    String connectorIdempotencyKeyHash = connectorIdempotencyKeyHash(request);
    request.markExecutionPending(clock.instant());
    auditEventService.record("CHANGE_REQUEST_EXECUTION_PENDING", "CHANGE_REQUEST", request.getId().toString(), null, "{\"adapter\":\"DEMO_ERP\",\"executionMode\":\"DEMO_ONLY\",\"idempotencyKeyHash\":\"" + connectorIdempotencyKeyHash + "\",\"networkCall\":false}");
    ConnectorSyncEvent sync = connectorSyncEventRepository.save(new ConnectorSyncEvent(request.getTenantId(), demoConnectionId(request.getTenantId()), IntegrationProviderType.OTHER_ERP, request.getRequestedAction(), "OUTBOUND_DEMO", clock.instant()));
    try {
      ExternalCommandResult result = "DRAFT_ORDER".equals(request.getSourceType())
          ? demoErpAdapterService.createDraftOrder(request)
          : demoErpAdapterService.createDraftQuote(request);
      if (result.success()) {
        request.markExecuted(result.externalReference(), connectorIdempotencyKeyHash, clock.instant());
        sync.complete(0, 0, 0, clock.instant());
        auditEventService.record("DEMO_ERP_COMMAND_EXECUTED", "CHANGE_REQUEST", request.getId().toString(), null, "{\"externalReference\":\"" + result.externalReference() + "\",\"executionMode\":\"DEMO_ONLY\",\"idempotencyKeyHash\":\"" + connectorIdempotencyKeyHash + "\",\"networkCall\":false}");
        emit(request, "CHANGE_REQUEST_EXECUTED_DEMO", "{\"externalReference\":\"" + result.externalReference() + "\"}");
      } else {
        request.markExecutionFailed(result.failureType(), result.message(), result.retryable(), clock.instant().plus(Duration.ofMinutes(15)), clock.instant());
        sync.fail(result.statusCode(), result.message(), clock.instant());
        auditEventService.record("DEMO_ERP_COMMAND_FAILED", "CHANGE_REQUEST", request.getId().toString(), null, "{\"errorCode\":\"" + result.statusCode() + "\",\"executionMode\":\"DEMO_ONLY\",\"idempotencyKeyHash\":\"" + connectorIdempotencyKeyHash + "\",\"networkCall\":false}");
        emit(request, "CHANGE_REQUEST_EXECUTION_FAILED_DEMO", "{\"errorCode\":\"" + result.statusCode() + "\"}");
      }
    } catch (RuntimeException ex) {
      request.markExecutionFailed(ConnectorFailureType.UNKNOWN, ex.getMessage(), true, clock.instant().plus(Duration.ofMinutes(15)), clock.instant());
      sync.fail("DEMO_ERP_EXCEPTION", ex.getMessage(), clock.instant());
      auditEventService.record("DEMO_ERP_COMMAND_FAILED", "CHANGE_REQUEST", request.getId().toString(), null, "{\"errorCode\":\"DEMO_ERP_EXCEPTION\",\"executionMode\":\"DEMO_ONLY\",\"idempotencyKeyHash\":\"" + connectorIdempotencyKeyHash + "\",\"networkCall\":false}");
    }
    return request;
  }

  @Transactional
  public ChangeRequest retryStage9DemoChangeRequest(UUID id) {
    ChangeRequest request = getMutable(id);
    if (!"FAILED".equals(request.getExecutionStatus()) || !request.isConnectorRetryable()) {
      throw new IllegalStateException("ChangeRequest failure is not retryable");
    }
    if (!"APPROVED".equals(request.getApprovalStatus())) {
      throw new IllegalStateException("Only approved ChangeRequests can be retried");
    }
    auditEventService.record("DEMO_ERP_MANUAL_RETRY_REQUESTED", "CHANGE_REQUEST", request.getId().toString(), null, "{\"attemptCount\":" + request.getConnectorAttemptCount() + ",\"networkCall\":false}");
    request.markExecutionDisabled("Manual retry requested for demo adapter");
    request.approve(request.getApprovedByUserId(), clock.instant());
    return executeStage9DemoChangeRequest(id);
  }

  @Transactional
  public ChangeRequest cancelStage9DemoChangeRequest(UUID id, String reason) {
    ChangeRequest request = getMutable(id);
    if ("EXECUTED".equals(request.getExecutionStatus())) {
      throw new IllegalStateException("Executed ChangeRequest cannot be cancelled");
    }
    request.cancelExecution(reason == null || reason.isBlank() ? "Cancelled by operator" : reason, clock.instant());
    auditEventService.record("CHANGE_REQUEST_CANCELLED", "CHANGE_REQUEST", request.getId().toString(), null, "{\"networkCall\":false}");
    emit(request, "CHANGE_REQUEST_CANCELLED", "{\"executionStatus\":\"CANCELLED\"}");
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

  private UUID demoConnectionId(UUID tenantId) {
    return integrationConnectionRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
        .filter(connection -> connection.getProviderType() == IntegrationProviderType.OTHER_ERP)
        .filter(connection -> connection.getDisplayName().contains("Demo ERP"))
        .map(IntegrationConnection::getId)
        .findFirst()
        .orElseGet(() -> {
          IntegrationConnection connection = integrationConnectionRepository.save(new IntegrationConnection(tenantId, IntegrationProviderType.OTHER_ERP, "Demo ERP Adapter", "DEMO_ERP_LOCAL", null, "demo://local", clock.instant()));
          connection.activate(clock.instant());
          auditEventService.record("DEMO_ERP_CONNECTION_CREATED", "INTEGRATION_CONNECTION", connection.getId().toString(), null, "{\"mode\":\"READ_ONLY\",\"networkCall\":false}");
          return connection.getId();
        });
  }

  private String connectorIdempotencyKey(ChangeRequest request) {
    return "demo-erp:" + request.getTenantId() + ":" + request.getId();
  }

  private String connectorIdempotencyKeyHash(ChangeRequest request) {
    return "sha256:" + sha256(connectorIdempotencyKey(request));
  }

  private void auditPolicyBlock(ChangeRequest request, String reasonCode) {
    auditEventService.record("CHANGE_REQUEST_EXECUTION_POLICY_BLOCKED", "CHANGE_REQUEST", request.getId().toString(), null, "{\"reasonCode\":\"" + reasonCode + "\",\"executionMode\":\"DEMO_ONLY\",\"networkCall\":false}");
  }

  private void requireEligibleQuote(DraftQuote quote) {
    if (!List.of("APPROVED", "APPROVED_INTERNAL").contains(quote.getStatus())) {
      throw new IllegalStateException("Draft quote must be approved before ChangeRequest creation");
    }
  }

  private void requireEligibleOrder(DraftOrder order) {
    if (!List.of("APPROVED", "APPROVED_INTERNAL").contains(order.getStatus())) {
      throw new IllegalStateException("Draft order must be approved before ChangeRequest creation");
    }
  }

  private void requireValidationBackedCase(UUID caseId) {
    if (caseId == null) {
      throw new IllegalStateException("ChangeRequest source must be linked to a validation-backed review case");
    }
    ExceptionCase reviewCase = exceptionCaseRepository.findByIdAndTenantId(caseId, TenantContext.requireTenantId()).orElseThrow(() -> new IllegalStateException("Source review case not found"));
    if ("BOT_CONVERSATION".equals(reviewCase.getSourceType()) || reviewCase.getValidationRunId() == null || reviewCase.getExtractionResultId() == null) {
      throw new IllegalStateException("Bot-only or non-validation-backed cases cannot create connector ChangeRequests");
    }
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

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is required for connector idempotency hashing", ex);
    }
  }
}
