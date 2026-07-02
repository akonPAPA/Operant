package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage9Dtos.*;
import com.orderpilot.application.services.integration.ChangeRequestService;
import com.orderpilot.application.services.integration.ConnectorExecutionSafetyService;
import com.orderpilot.application.services.integration.ConnectorSyncEventService;
import com.orderpilot.application.services.integration.IntegrationConnectionService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.integration.*;
import com.orderpilot.security.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class Stage9IntegrationController {
  private final IntegrationConnectionService integrationConnectionService;
  private final ChangeRequestService changeRequestService;
  private final ConnectorSyncEventService connectorSyncEventService;
  private final ConnectorExecutionSafetyService safetyService;
  private final RequestActorResolver actorResolver;

  public Stage9IntegrationController(IntegrationConnectionService integrationConnectionService, ChangeRequestService changeRequestService, ConnectorSyncEventService connectorSyncEventService, ConnectorExecutionSafetyService safetyService, RequestActorResolver actorResolver) {
    this.integrationConnectionService = integrationConnectionService;
    this.changeRequestService = changeRequestService;
    this.connectorSyncEventService = connectorSyncEventService;
    this.safetyService = safetyService;
    this.actorResolver = actorResolver;
  }

  @GetMapping("/api/stage9/connectors/policies")
  public Stage9ConnectorPolicyResponse policies() {
    return safetyService.policies();
  }

  @GetMapping("/api/stage9/integrations")
  public Stage9IntegrationListResponse integrations(@RequestParam(defaultValue = "50") int limit) {
    return new Stage9IntegrationListResponse(integrationConnectionService.list(limit).stream().map(this::toIntegration).toList());
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
  public Stage9ChangeRequestListResponse changeRequests(@RequestParam(defaultValue = "50") int limit) {
    return new Stage9ChangeRequestListResponse(changeRequestService.listChangeRequests(limit).stream().map(this::toChangeRequest).toList());
  }

  @GetMapping("/api/stage9/change-requests/{id}")
  public Stage9ChangeRequestResponse changeRequest(@PathVariable UUID id) {
    return toChangeRequest(changeRequestService.getChangeRequest(id));
  }

  @PostMapping("/api/stage9/change-requests")
  public Stage9ChangeRequestResponse createChangeRequest(@RequestBody Stage9ChangeRequestCreateRequest request, HttpServletRequest http) {
    // OP-CAP-17F: the connector ChangeRequest creator is server-resolved from the trusted (optionally
    // signed) actor context, never from a body-supplied actorId, so a caller cannot forge who created
    // a connector write request. The body carries business intent only.
    UUID actorId = actorResolver.resolveVerifiedActor(http, TenantContext.getTenantId().orElse(null));
    // Wave 01H Category C: the external-write payload is backend-owned. A null payload makes the
    // domain default to a neutral "{}" — the client cannot inject a payload (e.g. simulateFailure) to
    // steer demo execution. External execution stays DISABLED.
    return toChangeRequest(changeRequestService.createStage9DemoChangeRequest(request.sourceType(), request.sourceId(), request.requestedAction(), null, actorId));
  }

  @PostMapping("/api/stage9/change-requests/{id}/approve")
  public Stage9ChangeRequestResponse approve(@PathVariable UUID id, HttpServletRequest http) {
    // OP-CAP-17E: the connector ChangeRequest approver is server-resolved from the trusted
    // (optionally signed) actor context, never from the request body. Any body-supplied actorId is
    // ignored so a caller cannot forge who approved a connector write.
    UUID actorId = actorResolver.resolveVerifiedActor(http, TenantContext.getTenantId().orElse(null));
    return toChangeRequest(changeRequestService.approveChangeRequest(id, actorId));
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
  public Stage9ConnectorSyncRunListResponse syncRuns(@RequestParam(defaultValue = "50") int limit) {
    return new Stage9ConnectorSyncRunListResponse(connectorSyncEventService.list(limit).stream().map(this::toSyncRun).toList());
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
    return new Stage9IntegrationConnectionResponse(connection.getId(), connection.getProviderType().name(), connection.getDisplayName(), connection.getStatus(), connection.getMode(), connection.getConnectionKind(), connection.getLastSyncAt(), connection.getCreatedAt(), connection.getUpdatedAt());
  }

  private Stage9ChangeRequestResponse toChangeRequest(ChangeRequest request) {
    // Wave 01H Category D: expose only the operator-safe business rollup `status`; the raw
    // executionStatus / connector failure type / retryable flag are internal execution machinery.
    return new Stage9ChangeRequestResponse(request.getId(), status(request), request.getTargetSystem(), request.getTargetEntity(), request.getRequestedAction(), request.getSourceType(), request.getValidationStatus(), request.getApprovalStatus(), request.getExternalReference(), request.getCreatedAt(), request.getApprovedAt(), request.getRejectedAt(), request.getExecutedAt());
  }

  private Stage9ConnectorSyncRunResponse toSyncRun(ConnectorSyncEvent event) {
    return new Stage9ConnectorSyncRunResponse(event.getId(), event.getProviderType().name(), event.getSyncType(), event.getDirection(), event.getStatus(), event.getRecordsRead(), event.getRecordsWritten(), event.getRecordsFailed(), event.getErrorCode(), event.getStartedAt(), event.getFinishedAt());
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
