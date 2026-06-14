package com.orderpilot.api.rest;

import com.orderpilot.api.dto.OperatorCorrectionLearningDtos.ApproveCorrectionLearningRequest;
import com.orderpilot.api.dto.OperatorCorrectionLearningDtos.CorrectionLearningProjectionResponse;
import com.orderpilot.api.dto.OperatorCorrectionLearningDtos.OperatorCorrectionLearningRecordDto;
import com.orderpilot.api.dto.OperatorCorrectionLearningDtos.RecordOperatorCorrectionRequest;
import com.orderpilot.api.dto.OperatorCorrectionLearningDtos.RejectCorrectionLearningRequest;
import com.orderpilot.application.services.trust.OperatorCorrectionLearningService;
import com.orderpilot.application.services.trust.OperatorCorrectionLearningService.RecordCorrectionCommand;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.trust.ai.AiMemorySourceType;
import com.orderpilot.domain.trust.learning.OperatorCorrectionLearningRecord;
import com.orderpilot.domain.trust.learning.OperatorCorrectionStatus;
import com.orderpilot.domain.trust.learning.OperatorCorrectionType;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-18 Operator Correction Learning Loop.
 *
 * Tenant-scoped operator-correction capture + governance under {@code /api/v1/trust/operator-corrections}.
 * Reads require {@code TRUST_OPERATOR_CORRECTION_READ}; recording requires
 * {@code TRUST_OPERATOR_CORRECTION_WRITE}; approval/rejection require the dedicated
 * {@code TRUST_OPERATOR_CORRECTION_APPROVE}/{@code TRUST_OPERATOR_CORRECTION_REJECT} permissions (see
 * {@code ApiPermissionInterceptor}). Raw previous/corrected values are hashed server-side and never
 * returned. Tenant is resolved from context; ids are never trusted across tenants.
 */
@RestController
public class OperatorCorrectionLearningController {
  private final OperatorCorrectionLearningService service;

  public OperatorCorrectionLearningController(OperatorCorrectionLearningService service) {
    this.service = service;
  }

  @PostMapping("/api/v1/trust/operator-corrections")
  public OperatorCorrectionLearningRecordDto record(@RequestBody RecordOperatorCorrectionRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    OperatorCorrectionLearningRecord record = service.recordCorrection(new RecordCorrectionCommand(
        tenantId,
        parseEnum(OperatorCorrectionType.class, request.correctionType(), "correctionType"),
        parseEnum(AiMemorySourceType.class, request.sourceType(), "sourceType"),
        request.sourceId(),
        request.targetType(),
        request.targetId(),
        request.fieldKey(),
        request.previousValue(),
        request.correctedValue(),
        request.normalizedCorrection(),
        request.correctionSummary(),
        request.confidence(),
        null));
    return toDto(record);
  }

  @PostMapping("/api/v1/trust/operator-corrections/{id}/approve-learning")
  public CorrectionLearningProjectionResponse approve(
      @PathVariable UUID id, @RequestBody(required = false) ApproveCorrectionLearningRequest request) {
    return service.approveCorrectionForLearning(TenantContext.requireTenantId(), id, null);
  }

  @PostMapping("/api/v1/trust/operator-corrections/{id}/reject-learning")
  public OperatorCorrectionLearningRecordDto reject(
      @PathVariable UUID id, @RequestBody RejectCorrectionLearningRequest request) {
    return toDto(service.rejectCorrection(TenantContext.requireTenantId(), id,
        request == null ? null : request.reason(), null));
  }

  @GetMapping("/api/v1/trust/operator-corrections")
  public List<OperatorCorrectionLearningRecordDto> list(
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "correctionType", required = false) String correctionType,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "25") int size) {
    return service.listCorrections(TenantContext.requireTenantId(),
        parseEnum(OperatorCorrectionStatus.class, status, null),
        parseEnum(OperatorCorrectionType.class, correctionType, null), page, size)
        .stream().map(OperatorCorrectionLearningController::toDto).toList();
  }

  @GetMapping("/api/v1/trust/operator-corrections/{id}")
  public OperatorCorrectionLearningRecordDto get(@PathVariable UUID id) {
    return toDto(service.getCorrection(TenantContext.requireTenantId(), id));
  }

  // ----------------------------- mappers -----------------------------

  private static OperatorCorrectionLearningRecordDto toDto(OperatorCorrectionLearningRecord r) {
    return new OperatorCorrectionLearningRecordDto(
        r.getId(), r.getCorrectionType().name(), r.getSourceType().name(), r.getSourceId(),
        r.getTargetType(), r.getTargetId(), r.getFieldKey(), r.getPreviousValueHash(),
        r.getCorrectedValueHash(), r.getNormalizedCorrection(), r.getCorrectionSummary(), r.getConfidence(),
        r.getStatus().name(), r.isLearningEligible(), r.getLinkedAiMemoryRecordId(), r.getCreatedBy(),
        r.getCreatedAt(), r.getReviewedAt(), r.getRejectedAt(), r.getRejectionReason());
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String requiredField) {
    if (value == null || value.isBlank()) {
      if (requiredField != null) {
        throw new IllegalArgumentException(requiredField + " is required");
      }
      return null;
    }
    try {
      return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unknown " + type.getSimpleName() + ": " + value);
    }
  }
}
