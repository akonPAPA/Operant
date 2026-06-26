package com.orderpilot.application.services.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.SupportInternalDtos.ProcessingJobRepairResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.support.ProcessingJobStatusRepairValidator.RepairPlan;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.domain.intake.ProcessingJob;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import com.orderpilot.domain.support.DataRepairRequest;
import com.orderpilot.domain.support.DataRepairRequestRepository;
import com.orderpilot.domain.support.DataRepairTargetType;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-54 — the FIRST real, highly-constrained approved repair executor, bounded to ONE operational
 * target: {@link DataRepairTargetType#PROCESSING_JOB_STATUS_REPAIR}. It proves Operant can safely repair
 * production control-plane state (a wedged processing-job status) under approval, deterministic validation,
 * audit, idempotency, and a hard mutation boundary.
 *
 * <p>Law enforced here (proven by tests):
 * <ul>
 *   <li>execution requires an APPROVED, UNEXPIRED {@link DataRepairRequest} whose target type is exactly
 *       {@code PROCESSING_JOB_STATUS_REPAIR} — every other target fails closed before any read;</li>
 *   <li>the deterministic {@link ProcessingJobStatusRepairValidator} must pass; a failed validation denies
 *       BEFORE any mutation;</li>
 *   <li>the ONLY rows written are exactly one tenant-scoped {@code processing_job} row and the
 *       {@link DataRepairRequest} execution metadata — no order/quote/inventory/customer/price row, no
 *       connector/ERP/1C write, no outbox external command, no SQL/script;</li>
 *   <li>execution is idempotent: a request already {@code EXECUTED} replays its stored result and mutates
 *       nothing;</li>
 *   <li>every step (deny / validation-failed / started / executed / completed / replayed) is audited with
 *       safe operational metadata only.</li>
 * </ul>
 */
@Service
public class ProcessingJobRepairExecutor {
  private static final String ENTITY = "DATA_REPAIR_REQUEST";
  /** Safe, bounded failure token stamped on the repaired job's lastError — never free-form caller text. */
  private static final String REPAIR_REASON_TOKEN = "OPERATIONAL_STATUS_REPAIR";

  private final DataRepairRequestRepository requestRepository;
  private final ProcessingJobRepository processingJobRepository;
  private final ProcessingJobStatusRepairValidator validator;
  private final AuditEventService auditEventService;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public ProcessingJobRepairExecutor(
      DataRepairRequestRepository requestRepository,
      ProcessingJobRepository processingJobRepository,
      ProcessingJobStatusRepairValidator validator,
      AuditEventService auditEventService,
      ObjectMapper objectMapper,
      Clock clock) {
    this.requestRepository = requestRepository;
    this.processingJobRepository = processingJobRepository;
    this.validator = validator;
    this.auditEventService = auditEventService;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional
  public ProcessingJobRepairResponse execute(
      UUID tenantId,
      UUID staffUserId,
      UUID requestId,
      UUID processingJobId,
      String expectedCurrentStatus,
      String desiredStatus,
      String reason) {
    DataRepairRequest request = require(tenantId, requestId);
    Instant now = clock.instant();

    // 1) Target gate — only the one bounded target may ever reach the executor.
    if (request.getTargetType() != DataRepairTargetType.PROCESSING_JOB_STATUS_REPAIR) {
      auditDenied(tenantId, staffUserId, request, processingJobId, "WRONG_TARGET_TYPE");
      throw ProcessingJobRepairException.denied(
          "WRONG_TARGET_TYPE",
          "Repair denied: this request's target type is not eligible for processing-job status repair.");
    }
    // 2) Approval gate — must be approved and unexpired (break-glass does NOT bypass this in OP-CAP-54).
    if (!request.isApproved()) {
      String reasonCode = request.getApprovalStatus() == DataRepairRequest.ApprovalStatus.REJECTED
          ? "APPROVAL_REJECTED" : "APPROVAL_MISSING";
      auditDenied(tenantId, staffUserId, request, processingJobId, reasonCode);
      throw ProcessingJobRepairException.denied(reasonCode, "Repair denied: the request is not approved for execution.");
    }
    if (request.isApprovalExpired(now)) {
      auditDenied(tenantId, staffUserId, request, processingJobId, "APPROVAL_EXPIRED");
      throw ProcessingJobRepairException.denied("APPROVAL_EXPIRED", "Repair denied: the approval has expired.");
    }

    // 3) Idempotency — a request already executed replays its stored result and mutates nothing.
    if (request.isExecuted()) {
      auditReplayed(tenantId, staffUserId, request);
      return response(request, "Repair already executed — replaying the prior result. No change was applied.");
    }

    if (processingJobId == null) {
      auditValidationFailed(tenantId, staffUserId, request, null, "PROCESSING_JOB_ID_REQUIRED");
      throw ProcessingJobRepairException.validationFailed(
          "PROCESSING_JOB_ID_REQUIRED", "Repair denied: processingJobId is required.");
    }

    // 4) Load the target job under a tenant-scoped row lock (cross-tenant matches no row -> validation fail).
    ProcessingJob job = processingJobRepository
        .findWithLockByIdAndTenantId(processingJobId, tenantId)
        .orElse(null);
    if (job == null) {
      auditValidationFailed(tenantId, staffUserId, request, processingJobId, "JOB_NOT_FOUND");
      throw ProcessingJobRepairException.validationFailed(
          "JOB_NOT_FOUND", "Repair denied: the target processing job was not found for this tenant.");
    }

    // 5) Deterministic validation — denies before any mutation.
    RepairPlan plan;
    try {
      plan = validator.validate(job, expectedCurrentStatus, desiredStatus, now);
    } catch (ProcessingJobRepairException ex) {
      auditValidationFailed(tenantId, staffUserId, request, processingJobId, ex.getReasonCode());
      throw ex;
    }

    // 6) Execute — mutate exactly one processing_job row and stamp the request execution metadata.
    auditEvent("PROCESSING_JOB_REPAIR_EXECUTION_STARTED", tenantId, staffUserId, request, processingJobId,
        plan.previousStatus().name(), plan.targetStatus().name(), reason);

    job.applyOperationalStatusRepair(plan.targetStatus(), REPAIR_REASON_TOKEN, now);
    processingJobRepository.save(job);

    request.recordProcessingJobRepairExecution(
        job.getId(), plan.previousStatus().name(), plan.targetStatus().name(), staffUserId, now);
    requestRepository.save(request);

    auditEvent("PROCESSING_JOB_REPAIR_EXECUTED", tenantId, staffUserId, request, processingJobId,
        plan.previousStatus().name(), plan.targetStatus().name(), reason);
    auditEvent("DATA_REPAIR_EXECUTION_COMPLETED", tenantId, staffUserId, request, processingJobId,
        plan.previousStatus().name(), plan.targetStatus().name(), reason);

    return response(request, "Processing-job status repaired.");
  }

  private DataRepairRequest require(UUID tenantId, UUID requestId) {
    if (requestId == null) {
      throw new IllegalArgumentException("requestId is required");
    }
    return requestRepository.findByIdAndTenantId(requestId, tenantId)
        .orElseThrow(() -> new NotFoundException("Data-repair request not found"));
  }

  private ProcessingJobRepairResponse response(DataRepairRequest request, String message) {
    return new ProcessingJobRepairResponse(
        request.getId(),
        request.getTargetType().name(),
        request.getTargetProcessingJobId(),
        request.getExecutionStatus().name(),
        request.getPreviousStatus(),
        request.getNewStatus(),
        request.getExecutedAt(),
        message);
  }

  private void auditDenied(
      UUID tenantId, UUID staffUserId, DataRepairRequest request, UUID processingJobId, String reasonCode) {
    auditEventService.record(
        "PROCESSING_JOB_REPAIR_EXECUTION_DENIED", ENTITY, request.getId().toString(), staffUserId,
        metadata(map -> {
          map.put("decision", "DENIED");
          map.put("reasonCode", reasonCode);
          map.put("targetType", request.getTargetType().name());
          map.put("tenantId", tenantId.toString());
          map.put("approvalStatus", request.getApprovalStatus().name());
          if (processingJobId != null) {
            map.put("processingJobId", processingJobId.toString());
          }
        }));
  }

  private void auditValidationFailed(
      UUID tenantId, UUID staffUserId, DataRepairRequest request, UUID processingJobId, String reasonCode) {
    auditEventService.record(
        "PROCESSING_JOB_REPAIR_VALIDATION_FAILED", ENTITY, request.getId().toString(), staffUserId,
        metadata(map -> {
          map.put("decision", "VALIDATION_FAILED");
          map.put("reasonCode", reasonCode);
          map.put("targetType", request.getTargetType().name());
          map.put("tenantId", tenantId.toString());
          if (processingJobId != null) {
            map.put("processingJobId", processingJobId.toString());
          }
        }));
  }

  private void auditReplayed(UUID tenantId, UUID staffUserId, DataRepairRequest request) {
    auditEventService.record(
        "DATA_REPAIR_EXECUTION_REPLAYED", ENTITY, request.getId().toString(), staffUserId,
        metadata(map -> {
          map.put("decision", "REPLAYED");
          map.put("targetType", request.getTargetType().name());
          map.put("tenantId", tenantId.toString());
          map.put("processingJobId", String.valueOf(request.getTargetProcessingJobId()));
          map.put("previousStatus", request.getPreviousStatus());
          map.put("newStatus", request.getNewStatus());
          map.put("executionStatus", request.getExecutionStatus().name());
        }));
  }

  private void auditEvent(
      String action,
      UUID tenantId,
      UUID staffUserId,
      DataRepairRequest request,
      UUID processingJobId,
      String previousStatus,
      String newStatus,
      String reason) {
    auditEventService.record(action, ENTITY, request.getId().toString(), staffUserId,
        metadata(map -> {
          map.put("targetType", request.getTargetType().name());
          map.put("tenantId", tenantId.toString());
          map.put("processingJobId", String.valueOf(processingJobId));
          map.put("previousStatus", previousStatus);
          map.put("newStatus", newStatus);
          if (reason != null && !reason.isBlank()) {
            map.put("reason", reason.length() > 500 ? reason.substring(0, 500) : reason);
          }
        }));
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
