package com.orderpilot.application.services.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.SupportInternalDtos.DataRepairDryRunResponse;
import com.orderpilot.api.dto.SupportInternalDtos.DataRepairRequestResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.ConflictException;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.domain.support.DataRepairRequest;
import com.orderpilot.domain.support.DataRepairRequestRepository;
import com.orderpilot.domain.support.DataRepairTargetType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-51 / OP-CAP-52 — controlled data-repair foundation. A request starts as a <b>dry-run</b>: it records
 * the bounded intent (target area + reason) and produces a safe summary. OP-CAP-52 layers an approval gate
 * on top: a request can have execution approval REQUESTED, then APPROVED or REJECTED by a separate approver.
 * Even an APPROVED request never executes — the execute path is a STUB that always fails closed (denied
 * without a valid approval, execution-disabled with one). This service NEVER mutates a business row, NEVER
 * accepts arbitrary SQL/script, and NEVER executes. Real execution is not implemented and is disabled.
 */
@Service
public class DataRepairService {
  /** OP-CAP-52 — bounded lifetime of a data-repair execution approval; an expired approval cannot execute. */
  public static final Duration APPROVAL_TTL = Duration.ofHours(2);

  private static final String ENTITY = "DATA_REPAIR_REQUEST";

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

  /**
   * OP-CAP-52 — request execution approval for an existing dry-run request, attaching a backend-bounded
   * affected-target summary and a bounded approval expiry. Re-requesting an already pending/decided request
   * is a conflict. Emits an approval-requested audit. Mutates no business row.
   */
  @Transactional
  public DataRepairRequestResponse requestApproval(
      UUID tenantId, UUID staffUserId, UUID requestId, String affectedTargetSummaryRaw) {
    DataRepairRequest request = require(tenantId, requestId);
    if (request.getApprovalStatus() != DataRepairRequest.ApprovalStatus.NONE) {
      throw new ConflictException("Data-repair request already has an approval in flight or decided");
    }
    String summary = requireSummary(affectedTargetSummaryRaw);
    Instant now = clock.instant();
    request.requestApproval(summary, now.plus(APPROVAL_TTL), now);
    DataRepairRequest saved = requestRepository.save(request);

    auditEventService.record(
        "DATA_REPAIR_APPROVAL_REQUESTED",
        ENTITY,
        saved.getId().toString(),
        staffUserId,
        metadata(map -> {
          map.put("targetType", saved.getTargetType().name());
          map.put("tenantId", tenantId.toString());
          map.put("approvalStatus", saved.getApprovalStatus().name());
          map.put("executionStatus", saved.getExecutionStatus().name());
        }));
    return toResponse(saved, "Execution approval requested. Approval does not enable execution — execution "
        + "remains disabled in this stage.");
  }

  /**
   * OP-CAP-52 — approve a pending data-repair request. The approver is backend-resolved; an approver may not
   * approve their own request (separation of duties); approving a non-pending request is a conflict.
   * Approval alone NEVER executes anything.
   */
  @Transactional
  public DataRepairRequestResponse approve(UUID tenantId, UUID approverId, UUID requestId, String note) {
    return decide(tenantId, approverId, requestId, note, true);
  }

  /** OP-CAP-52 — reject a pending data-repair request; a rejected request can never be executed. */
  @Transactional
  public DataRepairRequestResponse reject(UUID tenantId, UUID approverId, UUID requestId, String note) {
    return decide(tenantId, approverId, requestId, note, false);
  }

  private DataRepairRequestResponse decide(
      UUID tenantId, UUID approverId, UUID requestId, String noteRaw, boolean approve) {
    DataRepairRequest request = require(tenantId, requestId);
    if (request.getApprovalStatus() != DataRepairRequest.ApprovalStatus.PENDING_APPROVAL) {
      throw new ConflictException("Data-repair request is not pending approval");
    }
    if (approverId != null && approverId.equals(request.getRequestedBy())) {
      auditEventService.record(
          "DATA_REPAIR_APPROVAL_DENIED",
          ENTITY,
          request.getId().toString(),
          approverId,
          metadata(map -> {
            map.put("decision", "DENIED");
            map.put("reasonCode", "SELF_APPROVAL_FORBIDDEN");
            map.put("tenantId", tenantId.toString());
          }));
      throw new SupportAccessDeniedException("Support access denied");
    }
    String note = normalizeNote(noteRaw);
    Instant now = clock.instant();
    if (approve) {
      request.approve(approverId, note, now);
    } else {
      request.reject(approverId, note, now);
    }
    DataRepairRequest saved = requestRepository.save(request);
    auditEventService.record(
        approve ? "DATA_REPAIR_APPROVED" : "DATA_REPAIR_REJECTED",
        ENTITY,
        saved.getId().toString(),
        approverId,
        metadata(map -> {
          map.put("decision", approve ? "APPROVED" : "REJECTED");
          map.put("targetType", saved.getTargetType().name());
          map.put("affectedTargetSummary", saved.getAffectedTargetSummary());
          map.put("tenantId", tenantId.toString());
        }));
    return toResponse(saved, approve
        ? "Execution approved. Execution remains DISABLED in this stage — no business data can be changed."
        : "Execution rejected. The request can never be executed.");
  }

  /**
   * OP-CAP-52 — the execution STUB. It proves the future execution gate exists WITHOUT enabling execution:
   * a missing/rejected/expired approval is denied (409), an approved+unexpired request returns
   * execution-disabled (501). Either path audits the attempt and mutates NO business row, runs NO SQL/script,
   * and calls NO connector/ERP. This method never writes anything except the attempt audit.
   */
  @Transactional
  public void attemptExecution(UUID tenantId, UUID staffUserId, UUID requestId) {
    DataRepairRequest request = require(tenantId, requestId);
    Instant now = clock.instant();

    if (!request.isApproved()) {
      String reasonCode = request.getApprovalStatus() == DataRepairRequest.ApprovalStatus.REJECTED
          ? "APPROVAL_REJECTED"
          : "APPROVAL_MISSING";
      auditExecutionDenied(tenantId, staffUserId, request, reasonCode);
      throw DataRepairExecutionException.denied(
          "Data-repair execution denied: the request is not approved for execution.");
    }
    if (request.isApprovalExpired(now)) {
      auditExecutionDenied(tenantId, staffUserId, request, "APPROVAL_EXPIRED");
      throw DataRepairExecutionException.denied(
          "Data-repair execution denied: the approval has expired.");
    }
    // Approved and unexpired — yet execution is intentionally not implemented in this stage.
    auditEventService.record(
        "DATA_REPAIR_EXECUTION_DISABLED",
        ENTITY,
        request.getId().toString(),
        staffUserId,
        metadata(map -> {
          map.put("decision", "EXECUTION_DISABLED");
          map.put("targetType", request.getTargetType().name());
          map.put("tenantId", tenantId.toString());
          map.put("executionStatus", request.getExecutionStatus().name());
        }));
    throw DataRepairExecutionException.disabled(
        "Data-repair execution is disabled in this stage. The approval gate exists but no executor is "
            + "implemented — no business data was read or mutated.");
  }

  private void auditExecutionDenied(
      UUID tenantId, UUID staffUserId, DataRepairRequest request, String reasonCode) {
    auditEventService.record(
        "DATA_REPAIR_EXECUTION_ATTEMPT_DENIED",
        ENTITY,
        request.getId().toString(),
        staffUserId,
        metadata(map -> {
          map.put("decision", "DENIED");
          map.put("reasonCode", reasonCode);
          map.put("targetType", request.getTargetType().name());
          map.put("tenantId", tenantId.toString());
          map.put("approvalStatus", request.getApprovalStatus().name());
        }));
  }

  private DataRepairRequest require(UUID tenantId, UUID requestId) {
    if (requestId == null) {
      throw new IllegalArgumentException("requestId is required");
    }
    return requestRepository.findByIdAndTenantId(requestId, tenantId)
        .orElseThrow(() -> new NotFoundException("Data-repair request not found"));
  }

  private DataRepairRequestResponse toResponse(DataRepairRequest request, String message) {
    return new DataRepairRequestResponse(
        request.getId(),
        request.getTenantId(),
        request.getTargetType().name(),
        request.getStatus().name(),
        request.getApprovalStatus().name(),
        request.getExecutionStatus().name(),
        request.getAffectedTargetSummary(),
        request.getApprovalExpiresAt(),
        message,
        request.getCreatedAt());
  }

  private static String requireSummary(String raw) {
    String summary = raw == null ? "" : raw.trim();
    if (summary.isEmpty()) {
      throw new IllegalArgumentException("affectedTargetSummary is required");
    }
    if (summary.length() > DataRepairRequest.MAX_TARGET_SUMMARY_LENGTH) {
      throw new IllegalArgumentException("affectedTargetSummary exceeds maximum length");
    }
    return summary;
  }

  private static String normalizeNote(String raw) {
    String note = raw == null ? null : raw.trim();
    if (note == null || note.isEmpty()) {
      return null;
    }
    if (note.length() > DataRepairRequest.MAX_DECISION_NOTE_LENGTH) {
      throw new IllegalArgumentException("decisionNote exceeds maximum length");
    }
    return note;
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
