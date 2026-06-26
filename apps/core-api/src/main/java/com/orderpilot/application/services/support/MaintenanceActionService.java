package com.orderpilot.application.services.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.SupportInternalDtos.MaintenanceActionRecordResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.domain.support.MaintenanceActionRecord;
import com.orderpilot.domain.support.MaintenanceActionRecordRepository;
import com.orderpilot.domain.support.MaintenanceActionType;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-51 — records maintenance/update actions for audit only. Persisting a record NEVER triggers a real
 * deployment, NEVER executes a migration, and NEVER calls an external system. It only writes one immutable
 * record row plus one audit event. The acting staff actor and tenant are backend-resolved (not from the
 * request body); the request carries action type + reason + optional target scope only.
 */
@Service
public class MaintenanceActionService {
  private final MaintenanceActionRecordRepository recordRepository;
  private final AuditEventService auditEventService;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public MaintenanceActionService(
      MaintenanceActionRecordRepository recordRepository,
      AuditEventService auditEventService,
      ObjectMapper objectMapper,
      Clock clock) {
    this.recordRepository = recordRepository;
    this.auditEventService = auditEventService;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional
  public MaintenanceActionRecordResponse record(
      UUID tenantId,
      UUID staffUserId,
      String actionTypeRaw,
      String reasonRaw,
      String targetScopeRaw) {
    MaintenanceActionType actionType = parseActionType(actionTypeRaw);
    String reason = requireReason(reasonRaw);
    String targetScope = normalizeTargetScope(targetScopeRaw, tenantId);

    MaintenanceActionRecord saved = recordRepository.save(new MaintenanceActionRecord(
        tenantId, actionType, staffUserId, reason, targetScope, clock.instant()));

    auditEventService.record(
        "MAINTENANCE_RECORD_CREATED",
        "MAINTENANCE_ACTION_RECORD",
        saved.getId().toString(),
        staffUserId,
        metadata(map -> {
          map.put("actionType", actionType.name());
          map.put("targetScope", targetScope);
          map.put("tenantId", tenantId.toString());
        }));

    return new MaintenanceActionRecordResponse(
        saved.getId(),
        saved.getTenantId(),
        saved.getActionType().name(),
        saved.getStatus().name(),
        saved.getTargetScope(),
        saved.getCreatedAt());
  }

  private static MaintenanceActionType parseActionType(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("actionType is required");
    }
    try {
      return MaintenanceActionType.valueOf(raw.trim());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unknown actionType: " + raw);
    }
  }

  private static String requireReason(String raw) {
    String reason = raw == null ? "" : raw.trim();
    if (reason.isEmpty()) {
      throw new IllegalArgumentException("reason is required");
    }
    if (reason.length() > MaintenanceActionRecord.MAX_REASON_LENGTH) {
      throw new IllegalArgumentException("reason exceeds maximum length");
    }
    return reason;
  }

  private static String normalizeTargetScope(String raw, UUID tenantId) {
    String scope = raw == null ? "" : raw.trim();
    if (scope.isEmpty()) {
      return "TENANT:" + tenantId;
    }
    if (scope.length() > MaintenanceActionRecord.MAX_TARGET_SCOPE_LENGTH) {
      throw new IllegalArgumentException("targetScope exceeds maximum length");
    }
    return scope;
  }

  private String metadata(java.util.function.Consumer<Map<String, Object>> builder) {
    Map<String, Object> map = new LinkedHashMap<>();
    builder.accept(map);
    try {
      return objectMapper.writeValueAsString(map);
    } catch (Exception ex) {
      return "{}";
    }
  }
}
