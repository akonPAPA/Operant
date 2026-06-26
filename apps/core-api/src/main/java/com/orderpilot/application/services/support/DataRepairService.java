package com.orderpilot.application.services.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.SupportInternalDtos.DataRepairDryRunResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.domain.support.DataRepairRequest;
import com.orderpilot.domain.support.DataRepairRequestRepository;
import com.orderpilot.domain.support.DataRepairTargetType;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-51 — controlled data-repair foundation. In this stage a request is <b>dry-run only</b>: it records
 * the bounded intent (target area + reason), produces a safe summary, and emits an audit event. It NEVER
 * mutates a business row, NEVER accepts arbitrary SQL/script, and NEVER executes. Real execution is not
 * implemented and is intentionally disabled.
 */
@Service
public class DataRepairService {
  private final DataRepairRequestRepository requestRepository;
  private final AuditEventService auditEventService;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public DataRepairService(
      DataRepairRequestRepository requestRepository,
      AuditEventService auditEventService,
      ObjectMapper objectMapper,
      Clock clock) {
    this.requestRepository = requestRepository;
    this.auditEventService = auditEventService;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional
  public DataRepairDryRunResponse requestDryRun(
      UUID tenantId,
      UUID staffUserId,
      String targetTypeRaw,
      String reasonRaw) {
    DataRepairTargetType targetType = parseTargetType(targetTypeRaw);
    String reason = requireReason(reasonRaw);

    DataRepairRequest saved = requestRepository.save(new DataRepairRequest(
        tenantId, targetType, staffUserId, reason, clock.instant()));

    auditEventService.record(
        "DATA_REPAIR_DRYRUN_REQUESTED",
        "DATA_REPAIR_REQUEST",
        saved.getId().toString(),
        staffUserId,
        metadata(map -> {
          map.put("targetType", targetType.name());
          map.put("tenantId", tenantId.toString());
          map.put("executionStatus", saved.getExecutionStatus().name());
        }));

    String summary = "Dry-run only: no business data was read or mutated. Real execution is disabled in this "
        + "stage and requires a future approval flow before any change could be applied.";

    return new DataRepairDryRunResponse(
        saved.getId(),
        saved.getTenantId(),
        saved.getTargetType().name(),
        saved.getStatus().name(),
        saved.getExecutionStatus().name(),
        summary,
        saved.getCreatedAt());
  }

  private static DataRepairTargetType parseTargetType(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("targetType is required");
    }
    try {
      return DataRepairTargetType.valueOf(raw.trim());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unknown targetType: " + raw);
    }
  }

  private static String requireReason(String raw) {
    String reason = raw == null ? "" : raw.trim();
    if (reason.isEmpty()) {
      throw new IllegalArgumentException("reason is required");
    }
    if (reason.length() > DataRepairRequest.MAX_REASON_LENGTH) {
      throw new IllegalArgumentException("reason exceeds maximum length");
    }
    return reason;
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
