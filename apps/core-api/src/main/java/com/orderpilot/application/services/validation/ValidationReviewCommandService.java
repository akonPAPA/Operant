package com.orderpilot.application.services.validation;

import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationApprovalRequestCommand;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationIssueResolutionRequest;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewActionResult;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewCorrectionRequest;
import com.orderpilot.api.dto.ValidationReviewCommandDtos;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.ExtractedField;
import com.orderpilot.domain.extraction.ExtractedFieldRepository;
import com.orderpilot.domain.extraction.ExtractedLineItem;
import com.orderpilot.domain.extraction.ExtractedLineItemRepository;
import com.orderpilot.domain.validation.ApprovalRequirement;
import com.orderpilot.domain.validation.ValidationIssue;
import com.orderpilot.domain.validation.ValidationIssueRepository;
import com.orderpilot.domain.validation.ValidationRun;
import com.orderpilot.domain.validation.ValidationRunRepository;
import com.orderpilot.domain.workspace.OperatorAction;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.orderpilot.application.services.workspace.OperatorActionService;

/**
 * OP-CAP-14C — operator validation-review command layer.
 *
 * <p>The first safe backend command surface for operator actions from the validation review
 * workspace: correct an advisory field/line item, resolve a validation issue, or raise an approval
 * request. Every command is tenant-scoped, validates current state before mutation, records a
 * tenant-owned {@link OperatorAction} paired with an {@code AuditEvent} (via {@link OperatorActionService}),
 * and stores bounded before/after snapshots only.
 *
 * <p>Trust boundary: these commands mutate advisory extraction rows and validation/review state only.
 * They never create a final quote/order, never write ERP/1C/connector/accounting/warehouse state, and
 * never modify product/customer/inventory/price master data. Raw AI payloads, document bodies, prompts,
 * secrets, tokens and stack traces are never stored or returned.
 */
@Service
public class ValidationReviewCommandService {
  private static final String ACTION_FIELD_CORRECTION = "VALIDATION_REVIEW_FIELD_CORRECTED";
  private static final String ACTION_LINE_CORRECTION = "VALIDATION_REVIEW_LINE_ITEM_CORRECTED";
  private static final String ACTION_ISSUE_RESOLUTION = "VALIDATION_REVIEW_ISSUE_RESOLVED";
  private static final String ACTION_APPROVAL_REQUEST = "VALIDATION_REVIEW_APPROVAL_REQUESTED";

  private static final String TARGET_FIELD = "EXTRACTED_FIELD";
  private static final String TARGET_LINE = "EXTRACTED_LINE_ITEM";
  private static final String TARGET_ISSUE = "VALIDATION_ISSUE";
  private static final String TARGET_APPROVAL = "APPROVAL_REQUIREMENT";

  // Issue statuses from which an operator decision may legally be applied.
  private static final Set<String> OPEN_ISSUE_STATUSES = Set.of("OPEN", "ACKNOWLEDGED");
  // Operator decision tokens accepted by the resolution command.
  private static final Set<String> RESOLUTIONS = Set.of(
      ValidationReviewCommandDtos.RESOLUTION_RESOLVED,
      ValidationReviewCommandDtos.RESOLUTION_IGNORED,
      ValidationReviewCommandDtos.RESOLUTION_ESCALATED);

  private final ValidationRunRepository runRepository;
  private final ExtractedFieldRepository fieldRepository;
  private final ExtractedLineItemRepository lineRepository;
  private final ValidationIssueRepository issueRepository;
  private final ApprovalRequirementService approvalRequirementService;
  private final OperatorActionService operatorActionService;
  private final JsonSupport jsonSupport;
  private final Clock clock;

  public ValidationReviewCommandService(
      ValidationRunRepository runRepository,
      ExtractedFieldRepository fieldRepository,
      ExtractedLineItemRepository lineRepository,
      ValidationIssueRepository issueRepository,
      ApprovalRequirementService approvalRequirementService,
      OperatorActionService operatorActionService,
      JsonSupport jsonSupport,
      Clock clock) {
    this.runRepository = runRepository;
    this.fieldRepository = fieldRepository;
    this.lineRepository = lineRepository;
    this.issueRepository = issueRepository;
    this.approvalRequirementService = approvalRequirementService;
    this.operatorActionService = operatorActionService;
    this.jsonSupport = jsonSupport;
    this.clock = clock;
  }

  @Transactional
  public ValidationReviewActionResult submitCorrection(
      UUID validationRunId, ValidationReviewCorrectionRequest request, UUID actorId) {
    UUID tenantId = TenantContext.requireTenantId();
    ValidationRun run = requireRun(tenantId, validationRunId);
    String targetType = upper(request.targetType());
    UUID targetId = request.targetId();
    if (targetId == null) throw new IllegalArgumentException("targetId is required");
    String reason = boundedReason(request.reason());

    if (ValidationReviewCommandDtos.TARGET_FIELD.equals(targetType)) {
      return correctField(tenantId, run, targetId, request, reason, actorId);
    }
    if (ValidationReviewCommandDtos.TARGET_LINE_ITEM.equals(targetType)) {
      return correctLineItem(tenantId, run, targetId, request, reason, actorId);
    }
    // Strict allowlist: source-evidence / extraction / audit / any other target is not correctable here.
    throw new IllegalArgumentException("unsupported_correction_target");
  }

  private ValidationReviewActionResult correctField(
      UUID tenantId, ValidationRun run, UUID fieldId, ValidationReviewCorrectionRequest request,
      String reason, UUID actorId) {
    ExtractedField field = fieldRepository.findByIdAndTenantId(fieldId, tenantId)
        .orElseThrow(() -> new NotFoundException("extracted_field_not_found"));
    if (!field.getExtractionResultId().equals(run.getExtractionResultId())) {
      throw new IllegalArgumentException("target_not_in_validation_run");
    }
    String corrected = boundedValue(request.correctedValue(), ValidationReviewCommandDtos.MAX_VALUE);
    if (corrected == null || corrected.isBlank()) throw new IllegalArgumentException("correctedValue is required for a FIELD correction");
    String before = field.getNormalizedValue();
    field.applyOperatorCorrection(corrected, clock.instant());
    fieldRepository.save(field);

    boolean approvalRequired = hasOpenApproval(run.getId(), null);
    Map<String, Object> metadata = baseMetadata(run, ValidationReviewCommandDtos.TARGET_FIELD, fieldId, reason, request.clientRequestId(), approvalRequired);
    metadata.put("beforeValue", boundedValue(before, ValidationReviewCommandDtos.MAX_VALUE));
    metadata.put("afterValue", corrected);
    OperatorAction action = operatorActionService.record(
        actorId, TARGET_FIELD, fieldId, ACTION_FIELD_CORRECTION,
        "Operator corrected an advisory extracted field value", jsonSupport.writeObject(metadata));

    return new ValidationReviewActionResult(
        action.getId(), run.getId(), ValidationReviewCommandDtos.TARGET_FIELD, fieldId, ACTION_FIELD_CORRECTION,
        "RECORDED", approvalRequired, null, null, null, actorId, action.getCreatedAt(),
        request.clientRequestId(), "Field correction recorded for operator review.");
  }

  private ValidationReviewActionResult correctLineItem(
      UUID tenantId, ValidationRun run, UUID lineId, ValidationReviewCorrectionRequest request,
      String reason, UUID actorId) {
    ExtractedLineItem line = lineRepository.findByIdAndTenantId(lineId, tenantId)
        .orElseThrow(() -> new NotFoundException("extracted_line_item_not_found"));
    if (!line.getExtractionResultId().equals(run.getExtractionResultId())) {
      throw new IllegalArgumentException("target_not_in_validation_run");
    }
    String correctedUom = request.correctedUom() == null || request.correctedUom().isBlank()
        ? null : boundedValue(request.correctedUom().trim().toUpperCase(Locale.ROOT), ValidationReviewCommandDtos.MAX_UOM);
    BigDecimal correctedQuantity = parsePositiveQuantity(request.correctedQuantity());
    if (correctedUom == null && correctedQuantity == null) {
      throw new IllegalArgumentException("A LINE_ITEM correction requires correctedQuantity and/or correctedUom");
    }
    Map<String, Object> before = new LinkedHashMap<>();
    before.put("quantity", line.getNormalizedQuantity() == null ? null : line.getNormalizedQuantity().toPlainString());
    before.put("uom", line.getNormalizedUom());
    if (correctedQuantity != null) line.correctQuantity(correctedQuantity, clock.instant());
    if (correctedUom != null) line.correctUom(correctedUom, clock.instant());
    lineRepository.save(line);

    boolean approvalRequired = hasOpenApproval(run.getId(), lineId);
    Map<String, Object> metadata = baseMetadata(run, ValidationReviewCommandDtos.TARGET_LINE_ITEM, lineId, reason, request.clientRequestId(), approvalRequired);
    metadata.put("before", before);
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("quantity", correctedQuantity == null ? before.get("quantity") : correctedQuantity.toPlainString());
    after.put("uom", correctedUom == null ? before.get("uom") : correctedUom);
    metadata.put("after", after);
    OperatorAction action = operatorActionService.record(
        actorId, TARGET_LINE, lineId, ACTION_LINE_CORRECTION,
        "Operator corrected an advisory extracted line item value", jsonSupport.writeObject(metadata));

    return new ValidationReviewActionResult(
        action.getId(), run.getId(), ValidationReviewCommandDtos.TARGET_LINE_ITEM, lineId, ACTION_LINE_CORRECTION,
        "RECORDED", approvalRequired, null, null, null, actorId, action.getCreatedAt(),
        request.clientRequestId(), "Line item correction recorded for operator review.");
  }

  @Transactional
  public ValidationReviewActionResult resolveIssue(
      UUID validationRunId, UUID issueId, ValidationIssueResolutionRequest request, UUID actorId) {
    UUID tenantId = TenantContext.requireTenantId();
    ValidationRun run = requireRun(tenantId, validationRunId);
    String resolution = upper(request.resolution());
    if (!RESOLUTIONS.contains(resolution)) throw new IllegalArgumentException("invalid_resolution");
    String reason = boundedReason(request.reason());
    if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason is required for an issue resolution");

    ValidationIssue issue = issueRepository.findByIdAndTenantId(issueId, tenantId)
        .orElseThrow(() -> new NotFoundException("validation_issue_not_found"));
    if (!issue.getValidationRunId().equals(validationRunId)) {
      throw new IllegalArgumentException("issue_not_in_validation_run");
    }

    String current = issue.getStatus();
    // Idempotent no-op: already in the requested resolution state — reflect it without a duplicate audit.
    if (resolution.equals(current)) {
      return new ValidationReviewActionResult(
          null, run.getId(), TARGET_ISSUE, issueId, ACTION_ISSUE_RESOLUTION, current, false, null, issueId,
          resolution, actorId, null, request.clientRequestId(), "Issue already in requested resolution state.");
    }
    if (!OPEN_ISSUE_STATUSES.contains(current)) {
      // Already decided (RESOLVED/IGNORED/ESCALATED/WAIVED/OVERRIDDEN/CORRECTED) into a different state.
      throw new IllegalArgumentException("illegal_issue_transition");
    }

    issue.setStatus(resolution, clock.instant());
    issueRepository.save(issue);

    Map<String, Object> metadata = baseMetadata(run, TARGET_ISSUE, issueId, reason, request.clientRequestId(), false);
    metadata.put("previousStatus", current);
    metadata.put("resolution", resolution);
    metadata.put("issueCode", issue.getIssueType());
    metadata.put("severity", issue.getSeverity());
    if (request.correctionActionId() != null) metadata.put("correctionActionId", request.correctionActionId().toString());
    OperatorAction action = operatorActionService.record(
        actorId, TARGET_ISSUE, issueId, ACTION_ISSUE_RESOLUTION,
        "Operator " + resolution.toLowerCase(Locale.ROOT) + " a validation issue", jsonSupport.writeObject(metadata));

    return new ValidationReviewActionResult(
        action.getId(), run.getId(), TARGET_ISSUE, issueId, ACTION_ISSUE_RESOLUTION, resolution, false, null,
        issueId, resolution, actorId, action.getCreatedAt(), request.clientRequestId(),
        "Issue marked " + resolution + ".");
  }

  @Transactional
  public ValidationReviewActionResult requestApproval(
      UUID validationRunId, ValidationApprovalRequestCommand request, UUID actorId) {
    UUID tenantId = TenantContext.requireTenantId();
    ValidationRun run = requireRun(tenantId, validationRunId);
    String reason = boundedReason(request.reason());
    if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason is required for an approval request");
    String requirementType = boundedValue(
        request.requirementType() == null || request.requirementType().isBlank() ? "OPERATOR_CORRECTION_REVIEW" : request.requirementType().trim(), 60);

    if (request.extractedLineItemId() != null) {
      ExtractedLineItem line = lineRepository.findByIdAndTenantId(request.extractedLineItemId(), tenantId)
          .orElseThrow(() -> new NotFoundException("extracted_line_item_not_found"));
      if (!line.getExtractionResultId().equals(run.getExtractionResultId())) {
        throw new IllegalArgumentException("target_not_in_validation_run");
      }
    }

    // Reuse existing approval infrastructure — creates a pending requirement (no new workflow engine).
    ApprovalRequirement approval = approvalRequirementService.create(run.getId(), request.extractedLineItemId(), requirementType, "MEDIUM", reason);

    Map<String, Object> metadata = baseMetadata(run, "APPROVAL_REQUEST", approval.getId(), reason, null, true);
    metadata.put("requirementType", requirementType);
    if (request.extractedLineItemId() != null) metadata.put("extractedLineItemId", request.extractedLineItemId().toString());
    OperatorAction action = operatorActionService.record(
        actorId, TARGET_APPROVAL, approval.getId(), ACTION_APPROVAL_REQUEST,
        "Operator requested approval for a validation-review correction", jsonSupport.writeObject(metadata));

    return new ValidationReviewActionResult(
        action.getId(), run.getId(), TARGET_APPROVAL, approval.getId(), ACTION_APPROVAL_REQUEST,
        approval.getStatus(), true, approval.getId(), null, null, actorId, action.getCreatedAt(),
        null, "Approval request created and pending review.");
  }

  // --- helpers -----------------------------------------------------------------------------------

  private ValidationRun requireRun(UUID tenantId, UUID validationRunId) {
    return runRepository.findByIdAndTenantId(validationRunId, tenantId)
        .orElseThrow(() -> new NotFoundException("validation_run_not_found"));
  }

  private boolean hasOpenApproval(UUID validationRunId, UUID lineItemId) {
    List<ApprovalRequirement> approvals = approvalRequirementService.list(validationRunId);
    return approvals.stream()
        .filter(a -> "OPEN".equals(a.getStatus()))
        .anyMatch(a -> lineItemId == null || lineItemId.equals(a.getExtractedLineItemId()));
  }

  private Map<String, Object> baseMetadata(ValidationRun run, String targetType, UUID targetId, String reason, String clientRequestId, boolean approvalRequired) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("validationRunId", run.getId().toString());
    metadata.put("targetType", targetType);
    metadata.put("targetId", targetId.toString());
    metadata.put("reason", reason);
    metadata.put("approvalRequired", approvalRequired);
    if (clientRequestId != null && !clientRequestId.isBlank()) metadata.put("clientRequestId", boundedValue(clientRequestId, 120));
    metadata.put("advisoryOnly", true);
    return metadata;
  }

  private BigDecimal parsePositiveQuantity(String raw) {
    if (raw == null || raw.isBlank()) return null;
    BigDecimal value;
    try {
      value = new BigDecimal(raw.trim());
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("correctedQuantity must be a number");
    }
    if (value.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("correctedQuantity must be greater than zero");
    return value;
  }

  private static String upper(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  private static String boundedReason(String reason) {
    return boundedValue(reason, ValidationReviewCommandDtos.MAX_REASON);
  }

  private static String boundedValue(String value, int max) {
    if (value == null) return null;
    return value.length() > max ? value.substring(0, max) : value;
  }
}
