package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage9Dtos.*;
import com.orderpilot.application.services.integration.ChangeRequestService;
import com.orderpilot.application.services.integration.ConnectorExecutionSafetyService;
import com.orderpilot.application.services.integration.ConnectorSyncEventService;
import com.orderpilot.application.services.integration.IntegrationConnectionService;
import com.orderpilot.domain.integration.*;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class Stage9IntegrationController {
  private final IntegrationConnectionService integrationConnectionService;
  private final ChangeRequestService changeRequestService;
  private final ConnectorSyncEventService connectorSyncEventService;
  private final ConnectorExecutionSafetyService safetyService;

  public Stage9IntegrationController(IntegrationConnectionService integrationConnectionService, ChangeRequestService changeRequestService, ConnectorSyncEventService connectorSyncEventService, ConnectorExecutionSafetyService safetyService) {
    this.integrationConnectionService = integrationConnectionService;
    this.changeRequestService = changeRequestService;
    this.connectorSyncEventService = connectorSyncEventService;
    this.safetyService = safetyService;
  }

  @GetMapping("/api/stage9/connectors/policies")
  public Stage9ConnectorPolicyResponse policies() {
    return safetyService.policies();
  }

  @GetMapping("/api/stage9/integrations")
  public Stage9IntegrationListResponse integrations() {
    return new Stage9IntegrationListResponse(integrationConnectionService.list().stream().map(this::toIntegration).toList());
  }

  @GetMapping("/api/stage9/integrations/{connectionId}")
  public Stage9IntegrationConnectionResponse integration(@PathVariable UUID connectionId) {
    return toIntegration(integrationConnectionService.get(connectionId));
  }

  @PostMapping("/api/stage9/integrations/demo-erp")
  public Stage9IntegrationConnectionResponse createDemoErp(@RequestBody(required = false) Stage9DemoErpConnectionRequest request) {
    String displayName = request == null || request.displayName() == null || request.displayName().isBlank() ? "Demo ERP Adapter" : request.displayName();
    IntegrationConnection connection = integrationConnectionService.createDraft(IntegrationProviderType.OTHER_ERP, displayName, "DEMO_ERP_LOCAL", null, "demo://local");
    return toIntegration(integrationConnectionService.activate(connection.getId()));
  }

  @GetMapping("/api/stage9/change-requests")
  public Stage9ChangeRequestListResponse changeRequests() {
    return new Stage9ChangeRequestListResponse(changeRequestService.listChangeRequests().stream().map(this::toChangeRequest).toList());
  }

  @GetMapping("/api/stage9/change-requests/{id}")
  public Stage9ChangeRequestResponse changeRequest(@PathVariable UUID id) {
    return toChangeRequest(changeRequestService.getChangeRequest(id));
  }

  @PostMapping("/api/stage9/change-requests")
  public Stage9ChangeRequestResponse createChangeRequest(@RequestBody Stage9ChangeRequestCreateRequest request) {
    return toChangeRequest(changeRequestService.createStage9DemoChangeRequest(request.sourceType(), request.sourceId(), request.requestedAction(), request.requestPayloadJson(), request.actorId()));
  }

  @PostMapping("/api/stage9/change-requests/{id}/approve")
  public Stage9ChangeRequestResponse approve(@PathVariable UUID id, @RequestBody(required = false) Stage9ApprovalRequest request) {
    return toChangeRequest(changeRequestService.approveChangeRequest(id, request == null ? null : request.actorId()));
  }

  @PostMapping("/api/stage9/change-requests/{id}/reject")
  public Stage9ChangeRequestResponse reject(@PathVariable UUID id, @RequestBody(required = false) Stage9ApprovalRequest request) {
    return toChangeRequest(changeRequestService.rejectChangeRequest(id, request == null ? null : request.reason()));
  }

  @PostMapping("/api/stage9/change-requests/{id}/execute")
  public Stage9ChangeRequestResponse execute(@PathVariable UUID id) {
    return toChangeRequest(changeRequestService.executeStage9DemoChangeRequest(id));
  }

  @GetMapping("/api/stage9/change-requests/{id}/execution-safety")
  public Stage9ExecutionSafetyResponse executionSafety(@PathVariable UUID id) {
    return safetyService.safety(id);
  }

  @PostMapping("/api/stage9/change-requests/{id}/retry")
  public Stage9ChangeRequestResponse retry(@PathVariable UUID id) {
    return toChangeRequest(changeRequestService.retryStage9DemoChangeRequest(id));
  }

  @PostMapping("/api/stage9/change-requests/{id}/cancel")
  public Stage9ChangeRequestResponse cancel(@PathVariable UUID id, @RequestBody(required = false) Stage9ApprovalRequest request) {
    return toChangeRequest(changeRequestService.cancelStage9DemoChangeRequest(id, request == null ? null : request.reason()));
  }

  @GetMapping("/api/stage9/connector-sync-runs")
  public Stage9ConnectorSyncRunListResponse syncRuns() {
    return new Stage9ConnectorSyncRunListResponse(connectorSyncEventService.list().stream().map(this::toSyncRun).toList());
  }

  @GetMapping("/api/stage9/connector-sync-runs/{id}")
  public Stage9ConnectorSyncRunResponse syncRun(@PathVariable UUID id) {
    return safetyService.syncRun(id);
  }

  @GetMapping("/api/stage9/connector-audit")
  public Stage9ConnectorAuditResponse connectorAudit() {
    return safetyService.audit();
  }

  private Stage9IntegrationConnectionResponse toIntegration(IntegrationConnection connection) {
    return new Stage9IntegrationConnectionResponse(connection.getId(), connection.getProviderType().name(), connection.getDisplayName(), connection.getStatus(), connection.getMode(), connection.getConnectionKind(), connection.getEndpointRef(), connection.getLastSyncAt(), connection.getCreatedAt(), connection.getUpdatedAt());
  }

  private Stage9ChangeRequestResponse toChangeRequest(ChangeRequest request) {
    String idempotencyKeyHash = request.getConnectorIdempotencyKey() == null ? safetyService.connectorIdempotencyKeyHash(request) : request.getConnectorIdempotencyKey();
    return new Stage9ChangeRequestResponse(request.getId(), status(request), request.getTargetSystem(), request.getTargetEntity(), request.getRequestedAction(), request.getSourceType(), request.getSourceId(), request.getValidationStatus(), request.getApprovalStatus(), request.getExecutionStatus(), request.getExternalReference(), request.getFailureReason(), request.getCreatedByUserId(), request.getApprovedByUserId(), request.getCreatedAt(), request.getApprovedAt(), request.getRejectedAt(), request.getExecutedAt(), idempotencyKeyHash, request.getConnectorAttemptCount(), request.getConnectorMaxAttempts(), request.getConnectorLastAttemptAt(), request.getConnectorNextRetryAt(), request.getConnectorFailureType() == null ? null : request.getConnectorFailureType().name(), request.isConnectorRetryable());
  }

  private Stage9ConnectorSyncRunResponse toSyncRun(ConnectorSyncEvent event) {
    return new Stage9ConnectorSyncRunResponse(event.getId(), event.getIntegrationConnectionId(), event.getProviderType().name(), event.getSyncType(), event.getDirection(), event.getStatus(), event.getRecordsRead(), event.getRecordsWritten(), event.getRecordsFailed(), event.getErrorCode(), event.getErrorMessage(), event.getStartedAt(), event.getFinishedAt());
  }

  private String status(ChangeRequest request) {
    if ("EXECUTED".equals(request.getExecutionStatus())) return "EXECUTED";
    if ("FAILED".equals(request.getExecutionStatus())) return "FAILED";
    if ("EXECUTION_PENDING".equals(request.getExecutionStatus())) return "EXECUTION_PENDING";
    if ("REJECTED".equals(request.getApprovalStatus())) return "REJECTED";
    if ("CANCELLED".equals(request.getApprovalStatus())) return "CANCELLED";
    if ("APPROVED".equals(request.getApprovalStatus())) return "APPROVED";
    if ("PENDING_APPROVAL".equals(request.getApprovalStatus())) return "PENDING_APPROVAL";
    return "DRAFT";
  }
}
