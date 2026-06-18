package com.orderpilot.application.services.integration;

import com.orderpilot.api.dto.Stage9Dtos.*;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.integration.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConnectorExecutionSafetyService {
  private final ChangeRequestRepository changeRequestRepository;
  private final ConnectorSyncEventRepository syncEventRepository;
  private final AuditEventRepository auditEventRepository;

  public ConnectorExecutionSafetyService(ChangeRequestRepository changeRequestRepository, ConnectorSyncEventRepository syncEventRepository, AuditEventRepository auditEventRepository) {
    this.changeRequestRepository = changeRequestRepository;
    this.syncEventRepository = syncEventRepository;
    this.auditEventRepository = auditEventRepository;
  }

  @Transactional(readOnly = true)
  public Stage9ConnectorPolicyResponse policies() {
    ConnectorExecutionPolicy policy = ConnectorExecutionPolicy.stage9DemoPolicy();
    return new Stage9ConnectorPolicyResponse(
        policy.executionMode().name(),
        policy.productionWritesEnabled(),
        policy.networkCallsAllowed(),
        "Production ERP/1C connectors remain disabled until separate security acceptance.");
  }

  @Transactional(readOnly = true)
  public Stage9ExecutionSafetyResponse safety(java.util.UUID changeRequestId) {
    ChangeRequest request = changeRequestRepository.findByIdAndTenantId(changeRequestId, TenantContext.requireTenantId())
        .orElseThrow(() -> new IllegalArgumentException("ChangeRequest not found"));
    return toSafety(request);
  }

  @Transactional(readOnly = true)
  public Stage9ConnectorSyncRunResponse syncRun(java.util.UUID id) {
    return syncEventRepository.findByIdAndTenantId(id, TenantContext.requireTenantId())
        .map(this::toSyncRun)
        .orElseThrow(() -> new IllegalArgumentException("Connector sync run not found"));
  }

  @Transactional(readOnly = true)
  public Stage9ConnectorAuditResponse audit() {
    List<Stage9ConnectorAuditEventResponse> events = auditEventRepository.findByTenantIdOrderByOccurredAtDesc(TenantContext.requireTenantId()).stream()
        .filter(event -> event.getAction().startsWith("CHANGE_REQUEST") || event.getAction().startsWith("DEMO_ERP") || event.getAction().startsWith("CONNECTOR"))
        .map(event -> new Stage9ConnectorAuditEventResponse(event.getAction(), event.getEntityType(), event.getOccurredAt()))
        .toList();
    return new Stage9ConnectorAuditResponse(events);
  }

  public Stage9ExecutionSafetyResponse toSafety(ChangeRequest request) {
    String failureType = request.getConnectorFailureType() == null ? null : request.getConnectorFailureType().name();
    return new Stage9ExecutionSafetyResponse(
        ConnectorExecutionMode.DEMO_ONLY.name(),
        request.getConnectorAttemptCount(),
        request.getConnectorMaxAttempts(),
        request.getConnectorLastAttemptAt(),
        request.getConnectorNextRetryAt(),
        failureType,
        request.isConnectorRetryable(),
        canRetry(request),
        canCancel(request),
        false,
        false);
  }

  public boolean canRetry(ChangeRequest request) {
    return "FAILED".equals(request.getExecutionStatus()) && request.isConnectorRetryable() && "APPROVED".equals(request.getApprovalStatus());
  }

  public boolean canCancel(ChangeRequest request) {
    return !"EXECUTED".equals(request.getExecutionStatus()) && !"CANCELLED".equals(request.getExecutionStatus()) && !"REJECTED".equals(request.getApprovalStatus());
  }

  private String connectorIdempotencyKey(ChangeRequest request) {
    return "demo-erp:" + request.getTenantId() + ":" + request.getId();
  }

  public String connectorIdempotencyKeyHash(ChangeRequest request) {
    return "sha256:" + sha256(connectorIdempotencyKey(request));
  }

  private Stage9ConnectorSyncRunResponse toSyncRun(ConnectorSyncEvent event) {
    return new Stage9ConnectorSyncRunResponse(event.getId(), event.getProviderType().name(), event.getSyncType(), event.getDirection(), event.getStatus(), event.getRecordsRead(), event.getRecordsWritten(), event.getRecordsFailed(), event.getErrorCode(), event.getStartedAt(), event.getFinishedAt());
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
