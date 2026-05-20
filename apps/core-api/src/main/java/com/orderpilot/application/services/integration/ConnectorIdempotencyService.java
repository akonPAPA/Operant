package com.orderpilot.application.services.integration;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.integration.ChangeRequest;
import com.orderpilot.domain.integration.ChangeRequestRepository;
import com.orderpilot.domain.integration.ConnectorCommand;
import com.orderpilot.domain.integration.ConnectorCommandRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConnectorIdempotencyService {
  private final ConnectorCommandRepository commandRepository;
  private final ChangeRequestRepository changeRequestRepository;
  private final AuditEventService auditEventService;
  private final Clock clock;

  public ConnectorIdempotencyService(ConnectorCommandRepository commandRepository, ChangeRequestRepository changeRequestRepository, AuditEventService auditEventService, Clock clock) {
    this.commandRepository = commandRepository;
    this.changeRequestRepository = changeRequestRepository;
    this.auditEventService = auditEventService;
    this.clock = clock;
  }

  @Transactional
  public ConnectorCommand createCommandFromApprovedChangeRequest(UUID changeRequestId, UUID outboxEventId, String connectorType, String operationType, String payloadJson) {
    UUID tenantId = TenantContext.requireTenantId();
    ChangeRequest changeRequest = changeRequestRepository.findByIdAndTenantId(changeRequestId, tenantId).orElseThrow(() -> new NotFoundException("Change request not found: " + changeRequestId));
    if (!"APPROVED".equals(changeRequest.getApprovalStatus())) {
      throw new IllegalArgumentException("Connector command requires an approved ChangeRequest");
    }
    requireValue(connectorType, "connectorType");
    requireValue(operationType, "operationType");
    String key = deriveIdempotencyKey(tenantId, connectorType, operationType, changeRequest.getId());
    var existing = commandRepository.findByTenantIdAndIdempotencyKey(tenantId, key);
    if (existing.isPresent()) {
      return existing.get();
    }
    ConnectorCommand command = commandRepository.save(new ConnectorCommand(tenantId, changeRequest.getId(), outboxEventId, connectorType, operationType, key, payloadJson, clock.instant()));
    auditEventService.record("CONNECTOR_COMMAND_CREATED_EXECUTION_DISABLED", "CONNECTOR_COMMAND", command.getId().toString(), null, "{\"externalExecution\":\"DISABLED\"}");
    return command;
  }

  @Transactional
  public ConnectorCommand deadLetter(UUID commandId, String reason, boolean retryable, Instant nextAttemptAt) {
    ConnectorCommand command = get(commandId);
    command.markDeadLettered(reason == null || reason.isBlank() ? "Connector command dead-lettered internally" : reason, retryable, nextAttemptAt, clock.instant());
    auditEventService.record("CONNECTOR_COMMAND_DEAD_LETTERED", "CONNECTOR_COMMAND", command.getId().toString(), null, "{\"externalExecution\":\"DISABLED\"}");
    return commandRepository.save(command);
  }

  @Transactional(readOnly = true)
  public ConnectorCommand get(UUID id) {
    return commandRepository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow(() -> new NotFoundException("Connector command not found: " + id));
  }

  @Transactional(readOnly = true)
  public List<ConnectorCommand> listCommands() {
    return commandRepository.findByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId());
  }

  public String deriveIdempotencyKey(UUID tenantId, String connectorType, String operationType, UUID sourceId) {
    requireValue(tenantId, "tenantId");
    requireValue(connectorType, "connectorType");
    requireValue(operationType, "operationType");
    requireValue(sourceId, "sourceId");
    return "connector:" + sha256(tenantId + ":" + connectorType + ":" + operationType + ":" + sourceId);
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  private static void requireValue(Object value, String label) {
    if (value == null || (value instanceof String text && text.isBlank())) {
      throw new IllegalArgumentException(label + " is required");
    }
  }
}
